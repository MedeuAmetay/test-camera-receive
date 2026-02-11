package kz.bdl.test.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import kz.bdl.test.model.LiveEventDto;
import kz.bdl.test.service.LiveEventHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.Base64;

@RestController
@Slf4j
@RequiredArgsConstructor
public class HikvisionEventController {

    private final LiveEventHub hub;

    @PostMapping("/hikvision/events")
    public ResponseEntity<String> receiveEvent(
            HttpServletRequest request,
            @RequestHeader HttpHeaders headers
    ) throws Exception {

        String contentType = request.getContentType();
        boolean isMultipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");

        Map<String, List<String>> headersMap = new LinkedHashMap<>();
        headers.forEach((k, v) -> headersMap.put(k, new ArrayList<>(v)));

        List<LiveEventDto.LivePartDto> partsOut = new ArrayList<>();

        if (isMultipart) {
            Collection<Part> parts = request.getParts();

            for (Part part : parts) {
                String partName = part.getName();
                String fileName = part.getSubmittedFileName();
                String partType = part.getContentType();

                byte[] bytes = part.getInputStream().readAllBytes();

                String b64 = Base64.getEncoder().encodeToString(bytes);

                String textPreview = null;
                if (looksLikeText(partType, fileName)) {
                    textPreview = new String(bytes, StandardCharsets.UTF_8);
                }

                partsOut.add(LiveEventDto.LivePartDto.builder()
                        .name(partName)
                        .filename(fileName)
                        .contentType(partType)
                        .size(bytes.length)
                        .base64(b64)
                        .textPreview(textPreview)
                        .build());
            }
        } else {
            byte[] body = request.getInputStream().readAllBytes();
            String b64 = Base64.getEncoder().encodeToString(body);

            String textPreview = new String(body, StandardCharsets.UTF_8);

            partsOut.add(LiveEventDto.LivePartDto.builder()
                    .name("raw-body")
                    .filename(null)
                    .contentType(contentType)
                    .size(body.length)
                    .base64(b64)
                    .textPreview(textPreview)
                    .build());
        }

        LiveEventDto dto = LiveEventDto.builder()
                .id(UUID.randomUUID().toString())
                .timestamp(OffsetDateTime.now())
                .method(request.getMethod())
                .path(request.getRequestURI())
                .remoteAddr(request.getRemoteAddr())
                .contentType(contentType)
                .headers(headersMap)
                .parts(partsOut)
                .build();

        hub.publish(dto);

        log.info("Hikvision event: ct={}, parts={}, from={}",
                contentType, partsOut.size(), request.getRemoteAddr());

        return ResponseEntity.ok("OK");
    }

    private boolean looksLikeText(String contentType, String fileName) {
        String t = contentType == null ? "" : contentType.toLowerCase();
        if (t.contains("xml") || t.contains("json") || t.contains("text")) return true;
        String fn = fileName == null ? "" : fileName.toLowerCase();
        return fn.endsWith(".xml") || fn.endsWith(".json") || fn.endsWith(".txt") || fn.endsWith(".csv");
    }
}


