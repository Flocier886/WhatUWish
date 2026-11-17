package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 仅负责 Image 私有 SSE 的传输、顺序与白名单校验，不了解 Ledger、Artifact 或前端。 */
@Component
public class HttpImageGenerationStreamClient implements ImageGenerationStreamClient {

    private static final String PROGRESS_SCHEMA = "image_generation_progress_event.v1";
    private static final String RESULT_SCHEMA = "image_generation_result.v1";
    private static final String ARTIFACT_SCHEMA = "image_generation_artifact.v1";
    private static final int MAX_ERROR_RESPONSE_BYTES = 4096;
    private static final Set<String> PHASES = Set.of(
            "inputs_resolved", "generation_started", "artifact_created", "completed", "failed"
    );
    private static final Set<String> STATUSES = Set.of("running", "completed", "failed");
    private final HttpClient httpClient;

    public HttpImageGenerationStreamClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    HttpImageGenerationStreamClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public ImageGenerationFinalResult stream(
            String pythonService,
            String endpoint,
            ImageGenerationStartRequest request,
            Consumer<ImageGenerationStreamEvent> eventConsumer
    ) {
        URI uri = resolveUri(pythonService, endpoint);
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(20))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(request.toPythonPayload())))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    throw parseHttpError(response.statusCode(), readLimitedBody(body));
                }
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                return consume(reader.lines(), request.getExecutionId(), eventConsumer);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationStreamException("image_generation_stream_interrupted", "图片生成流式请求被中断", exception);
        } catch (IOException exception) {
            throw new ImageGenerationStreamException("image_generation_transport_error", "无法连接图片生成服务", exception);
        }
    }

    ImageGenerationFinalResult consume(
            Stream<String> lines,
            String expectedExecutionId,
            Consumer<ImageGenerationStreamEvent> eventConsumer
    ) {
        StreamState state = new StreamState();
        SseFrame frame = new SseFrame();
        try (Stream<String> stream = lines) {
            stream.forEach(line -> consumeLine(line, frame, expectedExecutionId, eventConsumer, state));
        } catch (ImageGenerationStreamException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ImageGenerationStreamException("image_generation_stream_parse_failed", "图片生成 SSE 流无法解析", exception);
        }
        if (frame.hasData()) dispatch(frame, expectedExecutionId, eventConsumer, state);
        if (state.result == null) {
            throw new ImageGenerationStreamException("image_generation_stream_terminated", "图片生成流在返回终态前结束");
        }
        return state.result;
    }

    private void consumeLine(
            String line, SseFrame frame, String expectedExecutionId, Consumer<ImageGenerationStreamEvent> consumer,
            StreamState state
    ) {
        if (line == null || line.isEmpty()) {
            if (frame.hasData()) dispatch(frame, expectedExecutionId, consumer, state);
            frame.clear();
            return;
        }
        if (line.startsWith(":")) return;
        if (line.startsWith("event:")) frame.eventType = line.substring("event:".length()).trim();
        else if (line.startsWith("data:")) frame.appendData(line.substring("data:".length()).trim());
    }

    private void dispatch(
            SseFrame frame, String expectedExecutionId, Consumer<ImageGenerationStreamEvent> consumer,
            StreamState state
    ) {
        if (!StringUtils.hasText(frame.eventType) || !StringUtils.hasText(frame.data)) {
            throw protocolError("图片生成 SSE frame 缺少事件类型或数据");
        }
        if ("result".equals(frame.eventType)) {
            if (state.result != null) throw protocolError("图片生成 SSE 重复返回终态结果");
            ImageGenerationFinalResult result = parseFinalResult(frame.data, expectedExecutionId);
            if (!state.terminalReached || !result.getStatus().equals(state.terminalStatus)) {
                throw protocolError("图片生成 SSE 终态结果未与稳定阶段一致");
            }
            state.result = result;
            return;
        }
        if (state.result != null || state.terminalReached) throw protocolError("图片生成在终态后继续发送事件");
        if (!Set.of("progress", "artifact", "error").contains(frame.eventType)) {
            throw protocolError("图片生成 SSE 包含未知事件类型");
        }
        ImageGenerationStreamEvent event = parseStableEvent(frame.eventType, frame.data, expectedExecutionId);
        if (event.getSequence() != state.lastStableSequence + 1) {
            throw protocolError("图片生成稳定阶段序号不连续或重复");
        }
        state.lastStableSequence = event.getSequence();
        if ("completed".equals(event.getPhase()) || "failed".equals(event.getPhase())) {
            state.terminalReached = true;
            state.terminalStatus = event.getStatus();
        }
        if (consumer != null) consumer.accept(event);
    }

    private ImageGenerationStreamEvent parseStableEvent(String streamType, String data, String expectedExecutionId) {
        JSONObject object = parseObject(data, "稳定阶段");
        ImageGenerationStreamEvent event = new ImageGenerationStreamEvent();
        event.setStreamEventType(streamType);
        event.setSchemaVersion(object.getString("schemaVersion"));
        event.setEventId(object.getString("eventId"));
        event.setExecutionId(object.getString("executionId"));
        event.setSequence(object.getInteger("sequence"));
        event.setPhase(object.getString("phase"));
        event.setStatus(object.getString("status"));
        event.setOccurredAt(parseInstant(object.getString("occurredAt")));
        event.setSummary(object.getString("summary"));
        event.setData(copyMap(object.getJSONObject("data")));
        if (!PROGRESS_SCHEMA.equals(event.getSchemaVersion()) || !expectedExecutionId.equals(event.getExecutionId())
                || !StringUtils.hasText(event.getEventId()) || event.getSequence() == null || event.getSequence() < 1
                || !PHASES.contains(event.getPhase()) || !STATUSES.contains(event.getStatus())
                || !StringUtils.hasText(event.getSummary()) || event.getSummary().length() > 1200
                || !validPhaseStatus(event)) {
            throw protocolError("图片生成稳定阶段不符合 image_generation_progress_event.v1");
        }
        if ("artifact_created".equals(event.getPhase())) validateArtifactEvent(event);
        return event;
    }

    private void validateArtifactEvent(ImageGenerationStreamEvent event) {
        Object artifact = event.getData().get("artifact");
        if (!(artifact instanceof Map<?, ?> values)) {
            throw protocolError("图片生成 artifact_created 阶段缺少有效 Artifact descriptor");
        }
        validateArtifactDescriptor(values);
    }

    private void validateArtifactDescriptor(Map<?, ?> values) {
        if (!ARTIFACT_SCHEMA.equals(String.valueOf(values.get("schemaVersion")))) {
            throw protocolError("图片生成 Artifact descriptor 协议版本不匹配");
        }
        if (org.apache.commons.lang3.StringUtils.isAnyBlank(
                value(values, "artifactId"), value(values, "name"), value(values, "artifactType"), value(values, "artifactRole"),
                value(values, "visibility"), value(values, "sourceType"), value(values, "sourceName"), value(values, "fileName"),
                value(values, "storageType"), value(values, "storageKey"), value(values, "storageRef"), value(values, "mimeType"),
                value(values, "fileHash"), value(values, "contentSummary")
        ) || longValue(values.get("fileSize")) == null || longValue(values.get("fileSize")) < 0) {
            throw protocolError("图片生成 Artifact descriptor 不完整");
        }
    }

    private ImageGenerationFinalResult parseFinalResult(String data, String expectedExecutionId) {
        ImageGenerationFinalResult result;
        try {
            result = JSON.parseObject(data, ImageGenerationFinalResult.class);
        } catch (RuntimeException exception) {
            throw new ImageGenerationStreamException("image_generation_protocol_error", "图片生成终态 JSON 无效", exception);
        }
        if (result == null || !RESULT_SCHEMA.equals(result.getSchemaVersion()) || !expectedExecutionId.equals(result.getExecutionId())
                || !StringUtils.hasText(result.getImageExecutionId()) || !STATUSES.contains(result.getStatus())
                || !StringUtils.hasText(result.getSummary()) || !List.of("images", "edits").contains(result.getMode())
                || result.getRequestedCount() == null || result.getRequestedCount() < 1 || result.getRequestedCount() > 10) {
            throw protocolError("图片生成终态不符合 image_generation_result.v1");
        }
        if ("completed".equals(result.getStatus())) {
            if (result.getArtifacts() == null || result.getArtifacts().isEmpty()) throw protocolError("成功终态缺少图片 Artifact");
            if (result.getArtifacts().size() > result.getRequestedCount()) throw protocolError("成功终态图片数量超过请求数量");
            Set<String> artifactIds = new java.util.HashSet<>();
            for (Map<String, Object> artifact : result.getArtifacts()) {
                if (artifact == null) throw protocolError("成功终态包含无效图片 Artifact descriptor");
                validateArtifactDescriptor(artifact);
                if (!artifactIds.add(value(artifact, "artifactId"))) throw protocolError("成功终态包含重复图片 Artifact");
            }
        } else if (!StringUtils.hasText(result.getErrorCode())) {
            throw protocolError("失败终态缺少受控错误码");
        }
        return result;
    }

    ImageGenerationStreamException parseHttpError(int statusCode, String responseBody) {
        if (statusCode == 422) {
            try {
                JSONObject object = JSON.parseObject(responseBody);
                if (object != null && "image_generation_request_invalid".equals(object.getString("errorCode"))) {
                    return new ImageGenerationStreamException("image_generation_request_invalid", "图片生成请求不符合输入契约。");
                }
            } catch (RuntimeException ignored) {
                // 非受控响应不会原样带回 ReAct observation。
            }
        }
        return new ImageGenerationStreamException("image_generation_http_error", "图片生成服务返回 HTTP " + statusCode);
    }

    private JSONObject parseObject(String data, String label) {
        try {
            JSONObject object = JSON.parseObject(data);
            if (object == null) throw protocolError("图片生成 " + label + " 不是 JSON 对象");
            return object;
        } catch (ImageGenerationStreamException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ImageGenerationStreamException("image_generation_protocol_error", "图片生成 " + label + " 不是合法 JSON", exception);
        }
    }

    private URI resolveUri(String pythonService, String endpoint) {
        if (!StringUtils.hasText(pythonService) || !StringUtils.hasText(endpoint)) {
            throw new ImageGenerationStreamException("image_generation_config_missing", "图片生成 Python 服务地址或 endpoint 未配置");
        }
        try {
            return URI.create(pythonService.replaceAll("/+$", "") + (endpoint.startsWith("/") ? endpoint : "/" + endpoint));
        } catch (IllegalArgumentException exception) {
            throw new ImageGenerationStreamException("image_generation_config_invalid", "图片生成 Python 服务地址无效", exception);
        }
    }

    private String readLimitedBody(InputStream body) throws IOException {
        if (body == null) return "";
        byte[] bytes = body.readNBytes(MAX_ERROR_RESPONSE_BYTES + 1);
        return new String(bytes, 0, Math.min(bytes.length, MAX_ERROR_RESPONSE_BYTES), StandardCharsets.UTF_8);
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new ImageGenerationStreamException("image_generation_protocol_error", "图片生成 occurredAt 无效", exception);
        }
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private String value(Map<?, ?> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        try {
            return value == null ? null : value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean validPhaseStatus(ImageGenerationStreamEvent event) {
        if ("completed".equals(event.getPhase())) return "completed".equals(event.getStatus());
        if ("failed".equals(event.getPhase())) return "failed".equals(event.getStatus());
        return "running".equals(event.getStatus());
    }

    private ImageGenerationStreamException protocolError(String message) {
        return new ImageGenerationStreamException("image_generation_protocol_error", message);
    }

    private static final class SseFrame {
        private String eventType;
        private String data = "";
        private void appendData(String value) { data = data.isEmpty() ? value : data + "\n" + value; }
        private boolean hasData() { return StringUtils.hasText(data); }
        private void clear() { eventType = null; data = ""; }
    }

    private static final class StreamState {
        private int lastStableSequence;
        private boolean terminalReached;
        private String terminalStatus;
        private ImageGenerationFinalResult result;
    }
}
