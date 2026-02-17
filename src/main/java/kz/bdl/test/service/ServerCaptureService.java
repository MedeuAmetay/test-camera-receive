package kz.bdl.test.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import kz.bdl.test.model.LiveEventDto;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ServerCaptureService {

    private static final Pattern ILLEGAL_CODE_PATTERN = Pattern.compile("(?i)<\\s*illegalCode\\s*>\\s*([^<\\s]+)\\s*<\\s*/\\s*illegalCode\\s*>");
    private static final Pattern ILLEGAL_NAME_PATTERN = Pattern.compile("(?i)<\\s*illegalName\\s*>\\s*([^<]+?)\\s*<\\s*/\\s*illegalName\\s*>");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean captureViolationsOnly = new AtomicBoolean(false);
    private final Object ioLock = new Object();
    private final Path storageRoot = Paths.get("capture-store");
    private final List<CapturedEventSummary> capturedEvents = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(storageRoot);
        loadExistingEvents();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setCaptureViolationsOnly(boolean value) {
        captureViolationsOnly.set(value);
    }

    public boolean isCaptureViolationsOnly() {
        return captureViolationsOnly.get();
    }

    public int eventCount() {
        return capturedEvents.size();
    }

    public String storageRootPath() {
        return storageRoot.toAbsolutePath().normalize().toString();
    }

    public List<CapturedEventSummary> listEvents() {
        return capturedEvents.stream()
                .sorted(Comparator.comparing(CapturedEventSummary::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<IllegalTypeSummary> listUniqueIllegalTypes() {
        Map<IllegalTypeKey, Long> counters = new LinkedHashMap<>();
        for (CapturedEventSummary event : capturedEvents) {
            List<IllegalTypeValue> illegalTypes = event.getIllegalTypes();
            if (illegalTypes == null || illegalTypes.isEmpty()) {
                continue;
            }
            for (IllegalTypeValue illegalType : illegalTypes) {
                if (illegalType == null) {
                    continue;
                }
                String code = normalizeIllegalValue(illegalType.illegalCode());
                String name = normalizeIllegalValue(illegalType.illegalName());
                if (code == null && name == null) {
                    continue;
                }
                counters.merge(new IllegalTypeKey(code, name), 1L, Long::sum);
            }
        }
        return counters.entrySet().stream()
                .sorted(Comparator
                        .comparing(Map.Entry<IllegalTypeKey, Long>::getValue, Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().illegalCode(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(e -> e.getKey().illegalName(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(e -> new IllegalTypeSummary(e.getKey().illegalCode(), e.getKey().illegalName(), e.getValue()))
                .toList();
    }

    public EventMetadata readMetadata(String eventId) throws IOException {
        Path metadataPath = resolveSafePath(storageRoot.resolve(eventId).resolve("metadata.json"));
        if (!Files.exists(metadataPath)) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataPath.toFile(), EventMetadata.class);
        } catch (IOException e) {
            log.warn("Invalid metadata for event {}", eventId, e);
            return null;
        }
    }

    public Resource readPartResource(String eventId, String fileName) throws IOException {
        Path partPath = resolveSafePath(storageRoot.resolve(eventId).resolve(fileName));
        if (!Files.exists(partPath) || Files.isDirectory(partPath)) {
            return null;
        }
        return new UrlResource(partPath.toUri());
    }

    public void cleanStorage() throws IOException {
        synchronized (ioLock) {
            Path normalizedRoot = storageRoot.toAbsolutePath().normalize();
            if (Files.exists(normalizedRoot)) {
                try (var walk = Files.walk(normalizedRoot)) {
                    walk.sorted(Comparator.reverseOrder())
                            .filter(path -> !path.equals(normalizedRoot))
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw e;
                }
            }
            Files.createDirectories(normalizedRoot);
            capturedEvents.clear();
        }
    }

    public void captureIfEnabled(LiveEventDto event) {
        if (!enabled.get()) {
            return;
        }

        List<LiveEventDto.LivePartDto> liveParts = event.getParts() == null ? List.of() : event.getParts();
        IllegalAnalysis illegalAnalysis = analyzeIllegalFromLiveParts(liveParts);
        String anprStatus = illegalAnalysis.status();
        if (captureViolationsOnly.get() && "ok".equals(anprStatus)) {
            log.debug("Skip normal ANPR event {} because capture mode is violations-only", event.getId());
            return;
        }

        synchronized (ioLock) {
            try {
                Files.createDirectories(storageRoot);

                String eventId = safeToken(event.getId());
                Path eventDir = storageRoot.resolve(eventId);
                Files.createDirectories(eventDir);

                List<PartMetadata> parts = new ArrayList<>();
                for (int i = 0; i < liveParts.size(); i++) {
                    LiveEventDto.LivePartDto p = liveParts.get(i);
                    String fileName = buildPartFileName(i, p.getFilename(), p.getContentType());
                    Path targetPath = resolveSafePath(eventDir.resolve(fileName));

                    byte[] bytes = decodeBase64Safe(p.getBase64());
                    Files.write(targetPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                    parts.add(new PartMetadata(
                            p.getName(),
                            p.getFilename(),
                            p.getContentType(),
                            p.getSize(),
                            p.getTextPreview(),
                            fileName
                    ));
                }

                EventMetadata metadata = new EventMetadata(
                        event.getId(),
                        event.getTimestamp(),
                        event.getMethod(),
                        event.getPath(),
                        event.getRemoteAddr(),
                        event.getContentType(),
                        event.getHeaders(),
                        parts
                );

                Path metadataPath = eventDir.resolve("metadata.json");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata);

                capturedEvents.removeIf(x -> Objects.equals(x.getId(), event.getId()));
                capturedEvents.add(new CapturedEventSummary(
                        event.getId(),
                        event.getTimestamp(),
                        event.getRemoteAddr(),
                        event.getMethod(),
                        event.getPath(),
                        event.getContentType(),
                        parts.size(),
                        anprStatus,
                        illegalAnalysis.illegalTypes()
                ));
            } catch (Exception e) {
                log.error("Failed to persist event {}", event.getId(), e);
            }
        }
    }

    private void loadExistingEvents() throws IOException {
        capturedEvents.clear();
        if (!Files.exists(storageRoot)) {
            return;
        }

        try (var dirs = Files.list(storageRoot)) {
            dirs.filter(Files::isDirectory).forEach(eventDir -> {
                Path metadataPath = eventDir.resolve("metadata.json");
                if (!Files.exists(metadataPath)) {
                    return;
                }
                try {
                    EventMetadata metadata = objectMapper.readValue(metadataPath.toFile(), EventMetadata.class);
                    int partsCount = metadata.parts() == null ? 0 : metadata.parts().size();
                    IllegalAnalysis illegalAnalysis = analyzeIllegalFromMetadata(metadata.parts(), eventDir);
                    capturedEvents.add(new CapturedEventSummary(
                            metadata.id(),
                            metadata.timestamp(),
                            metadata.remoteAddr(),
                            metadata.method(),
                            metadata.path(),
                            metadata.contentType(),
                            partsCount,
                            illegalAnalysis.status(),
                            illegalAnalysis.illegalTypes()
                    ));
                } catch (IOException e) {
                    log.warn("Skip invalid metadata file {}", metadataPath, e);
                }
            });
        }
    }

    private static String buildPartFileName(int index, String originalFilename, String contentType) {
        String safeBase = safeToken(originalFilename);
        if (!safeBase.isBlank() && !safeBase.equals("file")) {
            return safeBase;
        }

        String ext = extensionFromContentType(contentType);
        return "part-" + index + ext;
    }

    private static String extensionFromContentType(String contentType) {
        if (contentType == null) return ".bin";
        String t = contentType.toLowerCase();
        if (t.contains("json")) return ".json";
        if (t.contains("xml")) return ".xml";
        if (t.contains("jpeg")) return ".jpg";
        if (t.contains("png")) return ".png";
        if (t.contains("text/plain")) return ".txt";
        if (t.contains("csv")) return ".csv";
        return ".bin";
    }

    private static byte[] decodeBase64Safe(String base64) {
        if (base64 == null || base64.isBlank()) {
            return new byte[0];
        }
        try {
            return java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static String extractTextPreview(LiveEventDto.LivePartDto part, byte[] bytes) {
        if (part.getTextPreview() != null && !part.getTextPreview().isBlank()) {
            return part.getTextPreview();
        }
        String contentType = part.getContentType() == null ? "" : part.getContentType().toLowerCase();
        String filename = part.getFilename() == null ? "" : part.getFilename().toLowerCase();
        boolean looksLikeText = contentType.contains("xml") || contentType.contains("json") || contentType.contains("text")
                || filename.endsWith(".xml") || filename.endsWith(".json") || filename.endsWith(".txt") || filename.endsWith(".csv");
        return looksLikeText ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    private IllegalAnalysis analyzeIllegalFromLiveParts(List<LiveEventDto.LivePartDto> liveParts) {
        if (liveParts == null || liveParts.isEmpty()) {
            return new IllegalAnalysis(null, List.of());
        }
        boolean hasIllegalBlocks = false;
        boolean hasOk = false;
        LinkedHashSet<IllegalTypeValue> illegalTypes = new LinkedHashSet<>();
        for (int i = 0; i < liveParts.size(); i++) {
            LiveEventDto.LivePartDto part = liveParts.get(i);
            byte[] bytes = decodeBase64Safe(part.getBase64());
            String xmlText = extractTextPreview(part, bytes);
            IllegalTextInfo partInfo = extractIllegalTextInfo(xmlText);
            if (!partInfo.hasIllegalBlocks()) {
                continue;
            }
            hasIllegalBlocks = true;
            if ("ok".equals(partInfo.status())) {
                hasOk = true;
            }
            illegalTypes.addAll(partInfo.illegalTypes());
        }
        String status = hasIllegalBlocks ? (hasOk ? "ok" : "bad") : null;
        return new IllegalAnalysis(status, List.copyOf(illegalTypes));
    }

    private IllegalAnalysis analyzeIllegalFromMetadata(List<PartMetadata> parts, Path eventDir) {
        if (parts == null || parts.isEmpty()) {
            return new IllegalAnalysis(null, List.of());
        }
        boolean hasIllegalBlocks = false;
        boolean hasOk = false;
        LinkedHashSet<IllegalTypeValue> illegalTypes = new LinkedHashSet<>();
        for (PartMetadata part : parts) {
            String xmlText = part.textPreview();
            if ((xmlText == null || xmlText.isBlank()) && part.savedFile() != null) {
                try {
                    Path filePath = resolveSafePath(eventDir.resolve(part.savedFile()));
                    xmlText = Files.readString(filePath, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    xmlText = null;
                }
            }
            IllegalTextInfo partInfo = extractIllegalTextInfo(xmlText);
            if (!partInfo.hasIllegalBlocks()) {
                continue;
            }
            hasIllegalBlocks = true;
            if ("ok".equals(partInfo.status())) {
                hasOk = true;
            }
            illegalTypes.addAll(partInfo.illegalTypes());
        }
        String status = hasIllegalBlocks ? (hasOk ? "ok" : "bad") : null;
        return new IllegalAnalysis(status, List.copyOf(illegalTypes));
    }

    private static IllegalTextInfo extractIllegalTextInfo(String xmlText) {
        if (xmlText == null || xmlText.isBlank()) {
            return new IllegalTextInfo(false, null, List.of());
        }
        List<String> codeValues = matchGroups(ILLEGAL_CODE_PATTERN, xmlText).stream()
                .map(ServerCaptureService::normalizeIllegalValue)
                .toList();
        List<String> nameValues = matchGroups(ILLEGAL_NAME_PATTERN, xmlText).stream()
                .map(ServerCaptureService::normalizeIllegalValue)
                .toList();

        boolean hasCode = !codeValues.isEmpty();
        boolean hasName = !nameValues.isEmpty();
        boolean hasIllegalBlocks = hasCode || hasName;
        if (!hasIllegalBlocks) {
            return new IllegalTextInfo(false, null, List.of());
        }

        LinkedHashSet<IllegalTypeValue> illegalTypes = new LinkedHashSet<>();
        int max = Math.max(codeValues.size(), nameValues.size());
        for (int i = 0; i < max; i++) {
            String codeValue = i < codeValues.size() ? codeValues.get(i) : null;
            String nameValue = i < nameValues.size() ? nameValues.get(i) : null;
            if (codeValue != null || nameValue != null) {
                illegalTypes.add(new IllegalTypeValue(codeValue, nameValue));
            }
        }

        boolean ok = false;

        for (String codeValue : codeValues) {
            if (codeValue == null) {
                continue;
            }
            try {
                int code = Integer.parseInt(codeValue.trim());
                if (code == 0) {
                    ok = true;
                    break;
                }
            } catch (NumberFormatException ignored) {
                // Treat non-numeric code as not OK.
            }
        }

        for (String nameValue : nameValues) {
            if (nameValue != null && "normal".equalsIgnoreCase(nameValue.trim())) {
                ok = true;
                break;
            }
        }

        return new IllegalTextInfo(true, ok ? "ok" : "bad", List.copyOf(illegalTypes));
    }

    private static String normalizeIllegalValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> matchGroups(Pattern pattern, String text) {
        List<String> values = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    private static String safeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "file";
        }
        String sanitized = raw.replace("\\", "/");
        int idx = sanitized.lastIndexOf("/");
        if (idx >= 0) {
            sanitized = sanitized.substring(idx + 1);
        }
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.isBlank()) {
            return "file";
        }
        return sanitized;
    }

    private Path resolveSafePath(Path path) throws IOException {
        Path normalizedRoot = storageRoot.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IOException("Invalid path");
        }
        return normalizedPath;
    }

    @Getter
    public static class CapturedEventSummary {
        private final String id;
        private final OffsetDateTime timestamp;
        private final String remoteAddr;
        private final String method;
        private final String path;
        private final String contentType;
        private final int partsCount;
        private final String anprStatus;
        private final List<IllegalTypeValue> illegalTypes;

        public CapturedEventSummary(
                String id,
                OffsetDateTime timestamp,
                String remoteAddr,
                String method,
                String path,
                String contentType,
                int partsCount,
                String anprStatus,
                List<IllegalTypeValue> illegalTypes
        ) {
            this.id = id;
            this.timestamp = timestamp;
            this.remoteAddr = remoteAddr;
            this.method = method;
            this.path = path;
            this.contentType = contentType;
            this.partsCount = partsCount;
            this.anprStatus = anprStatus;
            this.illegalTypes = illegalTypes == null ? List.of() : List.copyOf(illegalTypes);
        }
    }

    private record IllegalAnalysis(String status, List<IllegalTypeValue> illegalTypes) {
    }

    private record IllegalTextInfo(boolean hasIllegalBlocks, String status, List<IllegalTypeValue> illegalTypes) {
    }

    private record IllegalTypeKey(String illegalCode, String illegalName) {
    }

    public record IllegalTypeValue(String illegalCode, String illegalName) {
    }

    public record IllegalTypeSummary(String illegalCode, String illegalName, long eventsCount) {
    }

    public record EventMetadata(
            String id,
            OffsetDateTime timestamp,
            String method,
            String path,
            String remoteAddr,
            String contentType,
            Map<String, List<String>> headers,
            List<PartMetadata> parts
    ) {
    }

    public record PartMetadata(
            String name,
            String filename,
            String contentType,
            long size,
            String textPreview,
            String savedFile
    ) {
    }
}
