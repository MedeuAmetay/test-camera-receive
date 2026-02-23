package kz.bdl.test.service;

import kz.bdl.test.model.camera.CameraPushModels;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CameraConfigPushService {

    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_CHARS = 120_000;
    private static final String HTTP_HOST_NOTIFICATION_PATH = "/ISAPI/Event/notification/httpHosts/1";
    private static final String MIXED_TARGET_PATH_BASE = "/ISAPI/Intelligent/channels/1/mixedTargetDetection";

    public CameraPushModels.Response pushToCameras(CameraPushModels.Request request) {
        List<String> targets = normalizeTargets(request.cameraTargets());
        String endpointPath = normalizeEndpointPath(request.endpointPath());
        int timeoutMs = normalizeTimeout(request.timeoutMs());
        CameraPushModels.Payload payload = normalizePayload(request.payload());
        String payloadXml = buildPayloadXml(payload);

        List<CameraPushModels.Result> results = new ArrayList<>();
        int successCount = 0;
        for (String target : targets) {
            long startedAt = System.currentTimeMillis();
            try {
                URI uri = buildTargetUri(target, endpointPath);
                RequestExecution execution = executePut(
                        uri,
                        payloadXml,
                        "application/xml; charset=UTF-8",
                        blankToNull(request.username()),
                        blankToNull(request.password()),
                        timeoutMs
                );
                boolean success = execution.statusCode() != null
                        && execution.statusCode() >= 200
                        && execution.statusCode() < 300;
                if (success) {
                    successCount++;
                }

                results.add(new CameraPushModels.Result(
                        target,
                        uri.toString(),
                        success,
                        execution.statusCode(),
                        execution.authType(),
                        System.currentTimeMillis() - startedAt,
                        limitText(execution.responseBody(), MAX_RESPONSE_CHARS),
                        execution.error()
                ));
            } catch (Exception e) {
                results.add(new CameraPushModels.Result(
                        target,
                        null,
                        false,
                        null,
                        "none",
                        System.currentTimeMillis() - startedAt,
                        "",
                        safeMessage(e)
                ));
            }
        }

        return new CameraPushModels.Response(
                OffsetDateTime.now(),
                payloadXml,
                successCount,
                Math.max(results.size() - successCount, 0),
                results
        );
    }

    public CameraPushModels.Response pushMixedTargetDetection(CameraPushModels.MixedTargetRequest request) {
        List<String> targets = normalizeTargets(request.cameraTargets());
        int timeoutMs = normalizeTimeout(request.timeoutMs());
        CameraPushModels.MixedTargetPayload payload = normalizeMixedTargetPayload(request.payload());
        String endpointPath = MIXED_TARGET_PATH_BASE + "?format=json";
        String requestBody = buildMixedTargetJson(payload);
        String contentType = "application/json; charset=UTF-8";

        List<CameraPushModels.Result> results = new ArrayList<>();
        int successCount = 0;
        for (String target : targets) {
            long startedAt = System.currentTimeMillis();
            try {
                URI uri = buildTargetUri(target, endpointPath);
                RequestExecution execution = executePut(
                        uri,
                        requestBody,
                        contentType,
                        blankToNull(request.username()),
                        blankToNull(request.password()),
                        timeoutMs
                );
                boolean success = execution.statusCode() != null
                        && execution.statusCode() >= 200
                        && execution.statusCode() < 300;
                if (success) {
                    successCount++;
                }

                results.add(new CameraPushModels.Result(
                        target,
                        uri.toString(),
                        success,
                        execution.statusCode(),
                        execution.authType(),
                        System.currentTimeMillis() - startedAt,
                        limitText(execution.responseBody(), MAX_RESPONSE_CHARS),
                        execution.error()
                ));
            } catch (Exception e) {
                results.add(new CameraPushModels.Result(
                        target,
                        null,
                        false,
                        null,
                        "none",
                        System.currentTimeMillis() - startedAt,
                        "",
                        safeMessage(e)
                ));
            }
        }

        return new CameraPushModels.Response(
                OffsetDateTime.now(),
                requestBody,
                successCount,
                Math.max(results.size() - successCount, 0),
                results
        );
    }

    private RequestExecution executePut(
            URI uri,
            String body,
            String contentType,
            String username,
            String password,
            int timeoutMs
    ) throws Exception {
        RawResponse first = rawPut(uri, body, contentType, null, timeoutMs);
        String digestChallenge = extractDigestChallenge(first.headers());
        if (first.statusCode() != 401 || digestChallenge == null) {
            return new RequestExecution(first.statusCode(), first.body(), null, "none");
        }

        if (username == null || password == null) {
            return new RequestExecution(
                    first.statusCode(),
                    first.body(),
                    "Digest auth required but username/password are empty.",
                    "digest"
            );
        }

        String authorization = buildDigestAuthorization(
                digestChallenge,
                "PUT",
                digestUriPath(uri),
                username,
                password
        );

        RawResponse second = rawPut(uri, body, contentType, authorization, timeoutMs);
        return new RequestExecution(second.statusCode(), second.body(), null, "digest");
    }

    private RawResponse rawPut(
            URI uri,
            String body,
            String contentType,
            String authorization,
            int timeoutMs
    ) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("PUT");
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("Accept", "*/*");
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bodyBytes.length);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(bodyBytes);
        }

        int statusCode = connection.getResponseCode();
        String  responseBody;
        InputStream in = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (in == null) {
            responseBody = "";
        } else {
            try (InputStream is = in) {
                responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Map<String, List<String>> headers = connection.getHeaderFields();
        connection.disconnect();
        return new RawResponse(statusCode, responseBody, headers == null ? Map.of() : headers);
    }

    private static String extractDigestChallenge(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.equalsIgnoreCase("WWW-Authenticate")) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null && value.regionMatches(true, 0, "Digest ", 0, 7)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String buildDigestAuthorization(
            String challenge,
            String method,
            String uriPath,
            String username,
            String password
    ) throws Exception {
        Map<String, String> attrs = parseDigestAttributes(challenge);
        String realm = attrs.get("realm");
        String nonce = attrs.get("nonce");
        if (realm == null || nonce == null) {
            throw new IllegalStateException("Invalid digest challenge: missing realm or nonce");
        }

        String algorithm = attrs.getOrDefault("algorithm", "MD5");
        String qop = chooseQop(attrs.get("qop"));
        String opaque = attrs.get("opaque");
        String cnonce = randomCnonce();
        String nc = "00000001";

        String ha1 = digestHex(username + ":" + realm + ":" + password, algorithm);
        if (algorithm.toLowerCase(Locale.ROOT).endsWith("-sess")) {
            ha1 = digestHex(ha1 + ":" + nonce + ":" + cnonce, algorithm);
        }
        String ha2 = digestHex(method + ":" + uriPath, algorithm);

        String response;
        if (qop != null) {
            response = digestHex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2, algorithm);
        } else {
            response = digestHex(ha1 + ":" + nonce + ":" + ha2, algorithm);
        }

        StringBuilder auth = new StringBuilder("Digest ");
        appendAuthQuoted(auth, "username", username);
        appendAuthQuoted(auth, "realm", realm);
        appendAuthQuoted(auth, "nonce", nonce);
        appendAuthQuoted(auth, "uri", uriPath);
        appendAuthQuoted(auth, "response", response);
        auth.append(", algorithm=").append(algorithm);
        if (opaque != null && !opaque.isBlank()) {
            appendAuthQuoted(auth, "opaque", opaque);
        }
        if (qop != null) {
            auth.append(", qop=").append(qop);
            auth.append(", nc=").append(nc);
            appendAuthQuoted(auth, "cnonce", cnonce);
        }
        return auth.toString();
    }

    private static Map<String, String> parseDigestAttributes(String challenge) {
        String body = challenge.trim();
        if (body.regionMatches(true, 0, "Digest ", 0, 7)) {
            body = body.substring(7);
        }

        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < body.length()) {
            while (i < body.length() && (body.charAt(i) == ' ' || body.charAt(i) == ',')) i++;
            if (i >= body.length()) break;

            int keyStart = i;
            while (i < body.length() && body.charAt(i) != '=' && body.charAt(i) != ',') i++;
            String key = body.substring(keyStart, i).trim().toLowerCase(Locale.ROOT);
            if (i >= body.length() || body.charAt(i) != '=') {
                while (i < body.length() && body.charAt(i) != ',') i++;
                continue;
            }
            i++;

            String value;
            if (i < body.length() && body.charAt(i) == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < body.length()) {
                    char c = body.charAt(i++);
                    if (c == '\\' && i < body.length()) {
                        sb.append(body.charAt(i++));
                        continue;
                    }
                    if (c == '"') break;
                    sb.append(c);
                }
                value = sb.toString();
            } else {
                int valueStart = i;
                while (i < body.length() && body.charAt(i) != ',') i++;
                value = body.substring(valueStart, i).trim();
            }
            if (!key.isBlank()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String chooseQop(String qopValue) {
        if (qopValue == null || qopValue.isBlank()) {
            return null;
        }
        String[] parts = qopValue.split(",");
        String first = null;
        for (String part : parts) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isBlank()) continue;
            if (first == null) first = p;
            if ("auth".equals(p)) return "auth";
        }
        return first;
    }

    private static String digestHex(String value, String algorithmToken) throws Exception {
        String algoUpper = algorithmToken == null ? "" : algorithmToken.toUpperCase(Locale.ROOT);
        String jcaAlgo = algoUpper.contains("SHA-256") ? "SHA-256" : "MD5";
        MessageDigest md = MessageDigest.getInstance(jcaAlgo);
        byte[] bytes = md.digest(value.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static void appendAuthQuoted(StringBuilder sb, String key, String value) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
            sb.append(", ");
        }
        sb.append(key).append("=\"").append(escapeForQuoted(value)).append('"');
    }

    private static String escapeForQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String digestUriPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            path = path + "?" + uri.getRawQuery();
        }
        return path;
    }

    private static String randomCnonce() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static URI buildTargetUri(String target, String endpointPath) {
        String normalized = target.trim();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            URI base = URI.create(normalized);
            String scheme = base.getScheme() == null ? "http" : base.getScheme();
            int port = base.getPort();
            String host = base.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Invalid camera target: " + target);
            }
            String portPart = port > 0 ? ":" + port : "";
            return URI.create(scheme + "://" + host + portPart + endpointPath);
        }
        return URI.create("http://" + normalized + endpointPath);
    }

    private static List<String> normalizeTargets(List<String> rawTargets) {
        if (rawTargets == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String raw : rawTargets) {
            if (raw == null) continue;
            String[] split = raw.split("[\\s,;]+");
            for (String token : split) {
                String trimmed = token.trim();
                if (trimmed.isBlank()) continue;
                String key = trimmed.toLowerCase(Locale.ROOT);
                if (seen.contains(key)) continue;
                seen.add(key);
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String normalizeEndpointPath(String endpointPath) {
        String normalized = endpointPath == null || endpointPath.isBlank()
                ? HTTP_HOST_NOTIFICATION_PATH
                : endpointPath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    private static int normalizeTimeout(Integer timeoutMs) {
        if (timeoutMs == null) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.max(2_000, Math.min(timeoutMs, 60_000));
    }

    private static CameraPushModels.Payload normalizePayload(CameraPushModels.Payload payload) {
        if (payload == null) {
            return defaultPayload();
        }
        return new CameraPushModels.Payload(
                payload.id() == null ? 1 : payload.id(),
                defaultIfBlank(payload.url(), "/events/camera/hxml"),
                defaultIfBlank(payload.protocolType(), "HTTP"),
                defaultIfBlank(payload.parameterFormatType(), "XML"),
                defaultIfBlank(payload.addressingFormatType(), "ipaddress"),
                defaultIfBlank(payload.ipAddress(), "10.141.0.104"),
                payload.portNo() == null ? 8081 : payload.portNo(),
                payload.userName() == null ? "" : payload.userName(),
                defaultIfBlank(payload.httpAuthenticationMethod(), "none"),
                defaultIfBlank(payload.detectionUpLoadPicturesType(), "all"),
                payload.videoUploadEnabled() != null && payload.videoUploadEnabled(),
                payload.heartbeat() == null ? 0 : payload.heartbeat(),
                defaultIfBlank(payload.eventMode(), "all"),
                payload.enabled() == null || payload.enabled(),
                payload.checkResponseEnabled() == null || payload.checkResponseEnabled()
        );
    }

    private static CameraPushModels.MixedTargetPayload normalizeMixedTargetPayload(CameraPushModels.MixedTargetPayload payload) {
        if (payload == null) {
            return defaultMixedTargetPayload();
        }
        boolean binaryUpload = payload.isSupportBinaryPicUp() != null && payload.isSupportBinaryPicUp();
        boolean convertToBmp = binaryUpload
                && payload.convertBinToBmpEnabled() != null
                && payload.convertBinToBmpEnabled();
        return new CameraPushModels.MixedTargetPayload(
                payload.enabled() != null && payload.enabled(),
                binaryUpload,
                convertToBmp
        );
    }

    private static CameraPushModels.Payload defaultPayload() {
        return new CameraPushModels.Payload(
                1,
                "/events/camera/hxml",
                "HTTP",
                "XML",
                "ipaddress",
                "10.141.0.104",
                8081,
                "",
                "none",
                "all",
                false,
                0,
                "all",
                true,
                true
        );
    }

    private static CameraPushModels.MixedTargetPayload defaultMixedTargetPayload() {
        return new CameraPushModels.MixedTargetPayload(
                false,
                false,
                false
        );
    }

    private static String buildPayloadXml(CameraPushModels.Payload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<HttpHostNotification version=\"2.0\" xmlns=\"http://www.isapi.org/ver20/XMLSchema\">\n");
        appendTag(sb, "id", String.valueOf(payload.id()), 1);
        appendTag(sb, "url", payload.url(), 1);
        appendTag(sb, "protocolType", payload.protocolType(), 1);
        appendTag(sb, "parameterFormatType", payload.parameterFormatType(), 1);
        appendTag(sb, "addressingFormatType", payload.addressingFormatType(), 1);
        appendTag(sb, "ipAddress", payload.ipAddress(), 1);
        appendTag(sb, "portNo", String.valueOf(payload.portNo()), 1);
        appendTag(sb, "userName", payload.userName(), 1);
        appendTag(sb, "httpAuthenticationMethod", payload.httpAuthenticationMethod(), 1);

        indent(sb, 1).append("<ANPR>\n");
        appendTag(sb, "detectionUpLoadPicturesType", payload.detectionUpLoadPicturesType(), 2);
        appendTag(sb, "videoUploadEnabled", String.valueOf(payload.videoUploadEnabled()), 2);
        indent(sb, 1).append("</ANPR>\n");

        indent(sb, 1).append("<SubscribeEvent>\n");
        appendTag(sb, "heartbeat", String.valueOf(payload.heartbeat()), 2);
        appendTag(sb, "eventMode", payload.eventMode(), 2);
        indent(sb, 1).append("</SubscribeEvent>\n");

        appendTag(sb, "enabled", String.valueOf(payload.enabled()), 1);
        appendTag(sb, "checkResponseEnabled", String.valueOf(payload.checkResponseEnabled()), 1);
        sb.append("</HttpHostNotification>\n");
        return sb.toString();
    }

    private static String buildMixedTargetJson(CameraPushModels.MixedTargetPayload payload) {
        return "{"
                + "\"MixedTargetDetection\":{"
                + "\"enabled\":" + payload.enabled() + ","
                + "\"TypesDetection\":[{\"ruleMode\":\"human\",\"enabled\":false}],"
                + "\"MEF\":{\"enabled\":false},"
                + "\"isSupportBinaryPicUp\":" + payload.isSupportBinaryPicUp() + ","
                + "\"convertBinToBmpEnabled\":" + payload.convertBinToBmpEnabled()
                + "}"
                + "}";
    }

    private static void appendTag(StringBuilder sb, String tag, String value, int level) {
        indent(sb, level)
                .append("<").append(tag).append(">")
                .append(escapeXml(value == null ? "" : value))
                .append("</").append(tag).append(">\n");
    }

    private static StringBuilder indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("    ");
        }
        return sb;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        if (throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }

    private static String limitText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...truncated...";
    }

    private record RawResponse(
            int statusCode,
            String body,
            Map<String, List<String>> headers
    ) {
    }

    private record RequestExecution(
            Integer statusCode,
            String responseBody,
            String error,
            String authType
    ) {
    }
}
