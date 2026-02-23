package kz.bdl.test.model.camera;

import java.time.OffsetDateTime;
import java.util.List;

public final class CameraPushModels {

    private CameraPushModels() {
    }

    public record Request(
            List<String> cameraTargets,
            String username,
            String password,
            String endpointPath,
            Integer timeoutMs,
            Payload payload
    ) {
    }

    public record Payload(
            Integer id,
            String url,
            String protocolType,
            String parameterFormatType,
            String addressingFormatType,
            String ipAddress,
            Integer portNo,
            String userName,
            String httpAuthenticationMethod,
            String detectionUpLoadPicturesType,
            Boolean videoUploadEnabled,
            Integer heartbeat,
            String eventMode,
            Boolean enabled,
            Boolean checkResponseEnabled
    ) {
    }

    public record MixedTargetRequest(
            List<String> cameraTargets,
            String username,
            String password,
            Integer timeoutMs,
            MixedTargetPayload payload
    ) {
    }

    public record MixedTargetPayload(
            Boolean enabled,
            Boolean isSupportBinaryPicUp,
            Boolean convertBinToBmpEnabled
    ) {
    }

    public record Result(
            String cameraTarget,
            String requestUrl,
            boolean success,
            Integer statusCode,
            String authType,
            long durationMs,
            String responseBody,
            String error
    ) {
    }

    public record Response(
            OffsetDateTime executedAt,
            String payloadXml,
            int successCount,
            int failureCount,
            List<Result> results
    ) {
    }
}
