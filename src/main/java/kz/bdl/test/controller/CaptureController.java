package kz.bdl.test.controller;

import kz.bdl.test.service.ServerCaptureService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class CaptureController {

    private final ServerCaptureService serverCaptureService;

    @GetMapping("/capture")
    public String capturePage() {
        return "ui/capture";
    }

    @PostMapping("/capture/start")
    @ResponseBody
    public Map<String, Object> startCapture() {
        serverCaptureService.setEnabled(true);
        return statusPayload();
    }

    @PostMapping("/capture/stop")
    @ResponseBody
    public Map<String, Object> stopCapture() {
        serverCaptureService.setEnabled(false);
        return statusPayload();
    }

    @PostMapping("/capture/clean")
    @ResponseBody
    public Map<String, Object> cleanCapture() throws Exception {
        serverCaptureService.cleanStorage();
        return statusPayload();
    }

    @GetMapping("/capture/api/status")
    @ResponseBody
    public Map<String, Object> status() {
        return statusPayload();
    }

    @GetMapping("/capture/api/events")
    @ResponseBody
    public Object events() {
        return serverCaptureService.listEvents();
    }

    @GetMapping("/capture/api/events/{eventId}")
    @ResponseBody
    public ResponseEntity<?> eventDetails(@PathVariable String eventId) throws Exception {
        ServerCaptureService.EventMetadata metadata = serverCaptureService.readMetadata(eventId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(metadata);
    }

    @GetMapping("/capture/files/{eventId}/{fileName:.+}")
    public ResponseEntity<?> eventFile(
            @PathVariable String eventId,
            @PathVariable String fileName
    ) throws Exception {
        Resource resource = serverCaptureService.readPartResource(eventId, fileName);
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String encodedFileName = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    private Map<String, Object> statusPayload() {
        return Map.of(
                "enabled", serverCaptureService.isEnabled(),
                "eventCount", serverCaptureService.eventCount(),
                "storageRoot", serverCaptureService.storageRootPath()
        );
    }
}
