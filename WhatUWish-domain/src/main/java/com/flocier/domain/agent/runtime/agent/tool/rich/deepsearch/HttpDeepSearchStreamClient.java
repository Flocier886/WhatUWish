package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 基于 JDK HttpClient 的 DeepSearch SSE 传输实现。
 *
 * <p>它只校验外部协议并产出 Java 读取模型，不理解 Tool Invocation、Ledger、Replay 或前端事件。</p>
 */
@Component
public class HttpDeepSearchStreamClient implements DeepSearchStreamClient {

    private static final String EVENT_SCHEMA_VERSION = "deep_search_event.v1";
    private static final String RESULT_SCHEMA_VERSION = "deep_search_result.v1";
    private static final int MAX_ERROR_RESPONSE_BYTES = 4096;
    private final HttpClient httpClient;

    public HttpDeepSearchStreamClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    HttpDeepSearchStreamClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public DeepSearchFinalResult stream(String pythonService,
                                        String endpoint,
                                        DeepSearchStartRequest request,
                                        Consumer<DeepSearchStreamEvent> eventConsumer) {
        URI uri = resolveUri(pythonService, endpoint);
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(25))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(request.getPayload())))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    throw parseHttpError(response.statusCode(), readLimitedBody(body));
                }
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                return consume(reader.lines(), eventConsumer);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeepSearchStreamException("deep_search_stream_interrupted", "DeepSearch stream was interrupted", exception);
        } catch (IOException exception) {
            throw new DeepSearchStreamException("deep_search_transport_error", "Unable to connect to DeepSearch service", exception);
        }
    }

    /**
     * 只识别 Python DeepSearch 明确承诺的验证错误协议；未知响应一律不透传，避免上游正文进入 observation。
     */
    DeepSearchStreamException parseHttpError(int statusCode, String responseBody) {
        if (statusCode == 422) {
            try {
                JSONObject object = JSON.parseObject(responseBody);
                if (object != null && "deep_search_request_invalid".equals(object.getString("errorCode"))) {
                    String detail = validationDetail(object.getJSONArray("violations"));
                    String message = "DeepSearch 请求不符合输入契约";
                    if (StringUtils.hasText(detail)) {
                        message += "：" + detail;
                    }
                    message += "。请缩小或修正对应输入后重试。";
                    return new DeepSearchStreamException("deep_search_request_invalid", message);
                }
            } catch (RuntimeException ignored) {
                // 错误响应不符合受控协议时，使用下面的通用安全错误。
            }
        }
        return new DeepSearchStreamException("deep_search_http_error",
                "DeepSearch service returned HTTP " + statusCode);
    }

    private String validationDetail(JSONArray violations) {
        if (violations == null || violations.isEmpty()) {
            return null;
        }
        JSONObject violation = violations.getJSONObject(0);
        if (violation == null) {
            return null;
        }
        String field = safeIdentifier(violation.getString("field"));
        String rule = safeIdentifier(violation.getString("rule"));
        Object limit = violation.get("limit");
        if (!StringUtils.hasText(field) || !StringUtils.hasText(rule)) {
            return null;
        }
        if ("too_long".equals(rule) && limit instanceof Number) {
            return "字段 " + field + " 的数量不能超过 " + limit;
        }
        if ("too_short".equals(rule) && limit instanceof Number) {
            return "字段 " + field + " 的数量不能少于 " + limit;
        }
        if (("greater_than_equal".equals(rule) || "less_than_equal".equals(rule)) && limit instanceof Number) {
            return "字段 " + field + " 不满足数值限制 " + limit;
        }
        return "字段 " + field + " 不满足 " + rule + " 约束";
    }

    private String safeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.matches("[A-Za-z0-9_.\\[\\]-]{1,120}") ? normalized : null;
    }

    private String readLimitedBody(InputStream body) throws IOException {
        if (body == null) {
            return "";
        }
        byte[] bytes = body.readNBytes(MAX_ERROR_RESPONSE_BYTES + 1);
        int length = Math.min(bytes.length, MAX_ERROR_RESPONSE_BYTES);
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    DeepSearchFinalResult consume(Stream<String> lines, Consumer<DeepSearchStreamEvent> eventConsumer) {
        AtomicReference<DeepSearchFinalResult> finalResult = new AtomicReference<>();
        SseFrame frame = new SseFrame();
        int[] lastSequence = {0};
        try (Stream<String> stream = lines) {
            stream.forEach(line -> consumeLine(line, frame, eventConsumer, finalResult, lastSequence));
        } catch (DeepSearchStreamException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DeepSearchStreamException("deep_search_stream_parse_failed", "DeepSearch SSE stream could not be parsed", exception);
        }
        if (frame.hasData()) {
            dispatch(frame, eventConsumer, finalResult, lastSequence);
        }
        DeepSearchFinalResult result = finalResult.get();
        if (result == null) {
            throw new DeepSearchStreamException("deep_search_stream_terminated", "DeepSearch stream ended before a result event");
        }
        return result;
    }

    private void consumeLine(String line,
                             SseFrame frame,
                             Consumer<DeepSearchStreamEvent> eventConsumer,
                             AtomicReference<DeepSearchFinalResult> finalResult,
                             int[] lastSequence) {
        if (line == null || line.isEmpty()) {
            if (frame.hasData()) {
                dispatch(frame, eventConsumer, finalResult, lastSequence);
            }
            frame.clear();
            return;
        }
        if (line.startsWith(":")) {
            return;
        }
        if (line.startsWith("event:")) {
            frame.setEventType(line.substring("event:".length()).trim());
            return;
        }
        if (line.startsWith("data:")) {
            frame.appendData(line.substring("data:".length()).trim());
        }
    }

    private void dispatch(SseFrame frame,
                          Consumer<DeepSearchStreamEvent> eventConsumer,
                          AtomicReference<DeepSearchFinalResult> finalResult,
                          int[] lastSequence) {
        String eventType = frame.getEventType();
        if (!StringUtils.hasText(eventType)) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch SSE frame is missing event type");
        }
        if (!StringUtils.hasText(frame.getData())) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch SSE frame is missing data");
        }
        if ("result".equals(eventType)) {
            if (finalResult.get() != null) {
                throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch stream emitted more than one result");
            }
            finalResult.set(parseFinalResult(frame.getData()));
            return;
        }
        if (!"progress".equals(eventType) && !"artifact".equals(eventType) && !"error".equals(eventType)) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "Unsupported DeepSearch SSE event: " + eventType);
        }
        if (finalResult.get() != null) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch stream emitted an event after result");
        }
        DeepSearchStreamEvent event = parseStreamEvent(eventType, frame.getData());
        if (event.getSequence() <= lastSequence[0]) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch event sequence is not strictly increasing");
        }
        lastSequence[0] = event.getSequence();
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }

    private DeepSearchStreamEvent parseStreamEvent(String eventType, String data) {
        JSONObject object = parseObject(data, "event");
        DeepSearchStreamEvent event = new DeepSearchStreamEvent();
        event.setStreamEventType(eventType);
        event.setSchemaVersion(object.getString("schemaVersion"));
        event.setEventId(object.getString("eventId"));
        event.setExecutionId(object.getString("executionId"));
        event.setSequence(object.getInteger("sequence"));
        event.setPhase(object.getString("phase"));
        event.setStatus(object.getString("status"));
        event.setOccurredAt(parseInstant(object.getString("occurredAt"), "event occurredAt"));
        event.setSummary(object.getString("summary"));
        event.setData(copyMap(object.getJSONObject("data")));
        event.setPresentation(copyMap(object.getJSONObject("presentation")));
        if (!EVENT_SCHEMA_VERSION.equals(event.getSchemaVersion())
                || !StringUtils.hasText(event.getEventId())
                || !StringUtils.hasText(event.getExecutionId())
                || event.getSequence() == null || event.getSequence() < 1
                || !StringUtils.hasText(event.getPhase())
                || !StringUtils.hasText(event.getStatus())
                || !StringUtils.hasText(event.getSummary())) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch event does not satisfy deep_search_event.v1");
        }
        return event;
    }

    private DeepSearchFinalResult parseFinalResult(String data) {
        JSONObject object = parseObject(data, "result");
        DeepSearchFinalResult result;
        try {
            result = JSON.parseObject(data, DeepSearchFinalResult.class);
        } catch (RuntimeException exception) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch result JSON is invalid", exception);
        }
        if (result == null || !RESULT_SCHEMA_VERSION.equals(result.getSchemaVersion())
                || !StringUtils.hasText(result.getExecutionId())
                || !StringUtils.hasText(result.getResearchExecutionId())
                || !StringUtils.hasText(result.getStatus())
                || !StringUtils.hasText(result.getDeliveryDisposition())
                || !StringUtils.hasText(result.getResearchScopeFingerprint())
                || !StringUtils.hasText(result.getDeliveryMode())
                || !StringUtils.hasText(result.getSummary())
                || !StringUtils.hasText(result.getPresentationSummary())
                || !StringUtils.hasText(result.getStopReason())) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch result does not satisfy deep_search_result.v1");
        }
        // 读取 schema 中 JSON 字段可避免 JSON 库因泛型擦除留下 null collection。
        if (result.getSources() == null) result.setSources(java.util.List.of());
        if (result.getEvidence() == null) result.setEvidence(java.util.List.of());
        if (result.getLimitations() == null) result.setLimitations(java.util.List.of());
        if (result.getStageSummaries() == null) result.setStageSummaries(java.util.List.of());
        if (result.getArtifacts() == null) result.setArtifacts(java.util.List.of());
        return result;
    }

    private JSONObject parseObject(String data, String frameType) {
        try {
            JSONObject object = JSON.parseObject(data);
            if (object == null) {
                throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch " + frameType + " is not a JSON object");
            }
            return object;
        } catch (DeepSearchStreamException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch " + frameType + " is not valid JSON", exception);
        }
    }

    private Instant parseInstant(String value, String fieldName) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new DeepSearchStreamException("deep_search_protocol_error", "DeepSearch " + fieldName + " is invalid", exception);
        }
    }

    private URI resolveUri(String pythonService, String endpoint) {
        if (!StringUtils.hasText(pythonService) || !StringUtils.hasText(endpoint)) {
            throw new DeepSearchStreamException("deep_search_config_missing", "DeepSearch service URL or endpoint is missing");
        }
        String service = pythonService.endsWith("/") ? pythonService.substring(0, pythonService.length() - 1) : pythonService;
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        try {
            return URI.create(service + path);
        } catch (IllegalArgumentException exception) {
            throw new DeepSearchStreamException("deep_search_config_invalid", "DeepSearch service URL is invalid", exception);
        }
    }

    private Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    /** 单个 SSE frame 的临时读取状态，只属于 HTTP transport，不进入领域模型。 */
    private static class SseFrame {
        private String eventType;
        private final StringBuilder data = new StringBuilder();

        String getEventType() { return eventType; }
        void setEventType(String eventType) { this.eventType = eventType; }
        String getData() { return data.toString(); }
        boolean hasData() { return data.length() > 0; }
        void appendData(String value) {
            if (data.length() > 0) data.append('\n');
            data.append(value);
        }
        void clear() {
            eventType = null;
            data.setLength(0);
        }
    }
}
