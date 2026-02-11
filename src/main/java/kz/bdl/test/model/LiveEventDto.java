package kz.bdl.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveEventDto {
    private String id;
    private OffsetDateTime timestamp;

    private String method;
    private String path;
    private String remoteAddr;
    private String contentType;

    private Map<String, List<String>> headers;

    private List<LivePartDto> parts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LivePartDto {
        private String name;
        private String filename;
        private String contentType;
        private long size;
        private String base64;
        private String textPreview;
    }
}
