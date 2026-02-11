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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ServerCaptureService {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final AtomicBoolean enabled = new AtomicBoolean(false);
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
            if (Files.exists(storageRoot)) {
                try (var walk = Files.walk(storageRoot)) {
                    walk.sorted(Comparator.reverseOrder())
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
            Files.createDirectories(storageRoot);
            capturedEvents.clear();
        }
    }

    public void captureIfEnabled(LiveEventDto event) {
        if (!enabled.get()) {
            return;
        }

        synchronized (ioLock) {
            try {
                Files.createDirectories(storageRoot);

                String eventId = safeToken(event.getId());
                Path eventDir = storageRoot.resolve(eventId);
                Files.createDirectories(eventDir);

                List<PartMetadata> parts = new ArrayList<>();
                List<LiveEventDto.LivePartDto> liveParts = event.getParts() == null ? List.of() : event.getParts();
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
                        parts.size()
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
                    capturedEvents.add(new CapturedEventSummary(
                            metadata.id(),
                            metadata.timestamp(),
                            metadata.remoteAddr(),
                            metadata.method(),
                            metadata.path(),
                            metadata.contentType(),
                            partsCount
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

        public CapturedEventSummary(String id, OffsetDateTime timestamp, String remoteAddr, String method, String path, String contentType, int partsCount) {
            this.id = id;
            this.timestamp = timestamp;
            this.remoteAddr = remoteAddr;
            this.method = method;
            this.path = path;
            this.contentType = contentType;
            this.partsCount = partsCount;
        }
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
