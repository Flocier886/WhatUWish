package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 只负责 Report 私有 SSE 传输与协议校验，不了解 Ledger、Artifact 运行时或前端状态。 */
@Component
public class HttpReportStreamClient implements ReportStreamClient {
    private static final String PROGRESS_SCHEMA = "report_progress_event.v1";
    private static final String DELTA_SCHEMA = "report_content_delta.v1";
    private static final String RESULT_SCHEMA = "report_result.v1";
    private static final String ARTIFACT_SCHEMA = "report_artifact.v1";
    private static final int MAX_ERROR_RESPONSE_BYTES = 4096;
    private static final int MAX_DELTA_CHARS = 4000;
    private final HttpClient httpClient;

    public HttpReportStreamClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    HttpReportStreamClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public ReportFinalResult stream(
            String pythonService,
            String endpoint,
            ReportStartRequest request,
            Consumer<ReportStreamEvent> eventConsumer,
            Consumer<ReportContentDelta> deltaConsumer
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
                return consume(reader.lines(), request.getExecutionId(), eventConsumer, deltaConsumer);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReportStreamException("report_stream_interrupted", "Report 流式请求被中断", exception);
        } catch (IOException exception) {
            throw new ReportStreamException("report_transport_error", "无法连接 Report 服务", exception);
        }
    }

    ReportStreamException parseHttpError(int statusCode, String responseBody) {
        if (statusCode == 422) {
            try {
                JSONObject object = JSON.parseObject(responseBody);
                if (object != null && "report_request_invalid".equals(object.getString("errorCode"))) {
                    String detail = validationDetail(object.getJSONArray("violations"));
                    return new ReportStreamException("report_request_invalid", StringUtils.hasText(detail)
                            ? "Report 请求不符合输入契约：" + detail + "。"
                            : "Report 请求不符合输入契约。"
                    );
                }
            } catch (RuntimeException ignored) {
                // 非受控上游响应不能原样带回 ReAct。
            }
        }
        return new ReportStreamException("report_http_error", "Report 服务返回 HTTP " + statusCode);
    }

    ReportFinalResult consume(
            Stream<String> lines,
            String expectedExecutionId,
            Consumer<ReportStreamEvent> eventConsumer,
            Consumer<ReportContentDelta> deltaConsumer
    ) {
        AtomicReference<ReportFinalResult> result = new AtomicReference<>();
        SseFrame frame = new SseFrame();
        int[] lastStableSequence = {0};
        int[] lastChunkIndex = {0};
        try (Stream<String> stream = lines) {
            stream.forEach(line -> consumeLine(line, frame, expectedExecutionId, eventConsumer, deltaConsumer,
                    result, lastStableSequence, lastChunkIndex));
        } catch (ReportStreamException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReportStreamException("report_stream_parse_failed", "Report SSE 流无法解析", exception);
        }
        if (frame.hasData()) {
            dispatch(frame, expectedExecutionId, eventConsumer, deltaConsumer, result, lastStableSequence, lastChunkIndex);
        }
        ReportFinalResult finalResult = result.get();
        if (finalResult == null) {
            throw new ReportStreamException("report_stream_terminated", "Report 流在返回终态前结束");
        }
        return finalResult;
    }

    private void consumeLine(
            String line,
            SseFrame frame,
            String expectedExecutionId,
            Consumer<ReportStreamEvent> eventConsumer,
            Consumer<ReportContentDelta> deltaConsumer,
            AtomicReference<ReportFinalResult> result,
            int[] lastStableSequence,
            int[] lastChunkIndex
    ) {
        if (line == null || line.isEmpty()) {
            if (frame.hasData()) {
                dispatch(frame, expectedExecutionId, eventConsumer, deltaConsumer, result, lastStableSequence, lastChunkIndex);
            }
            frame.clear();
            return;
        }
        if (line.startsWith(":")) return;
        if (line.startsWith("event:")) {
            frame.eventType = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
            frame.appendData(line.substring("data:".length()).trim());
        }
    }

    private void dispatch(
            SseFrame frame,
            String expectedExecutionId,
            Consumer<ReportStreamEvent> eventConsumer,
            Consumer<ReportContentDelta> deltaConsumer,
            AtomicReference<ReportFinalResult> result,
            int[] lastStableSequence,
            int[] lastChunkIndex
    ) {
        if (!StringUtils.hasText(frame.eventType) || !StringUtils.hasText(frame.data)) {
            throw new ReportStreamException("report_protocol_error", "Report SSE frame 缺少事件类型或数据");
        }
        if ("result".equals(frame.eventType)) {
            if (result.get() != null) {
                throw new ReportStreamException("report_protocol_error", "Report SSE 重复返回终态结果");
            }
            result.set(parseFinalResult(frame.data, expectedExecutionId));
            return;
        }
        if (result.get() != null) {
            throw new ReportStreamException("report_protocol_error", "Report 在终态后继续发送事件");
        }
        if ("delta".equals(frame.eventType)) {
            ReportContentDelta delta = parseDelta(frame.data, expectedExecutionId);
            if (delta.getChunkIndex() <= lastChunkIndex[0]) {
                throw new ReportStreamException("report_protocol_error", "Report delta 序号不连续或重复");
            }
            lastChunkIndex[0] = delta.getChunkIndex();
            if (deltaConsumer != null) deltaConsumer.accept(delta);
            return;
        }
        if (!"progress".equals(frame.eventType) && !"artifact".equals(frame.eventType) && !"error".equals(frame.eventType)) {
            throw new ReportStreamException("report_protocol_error", "Report SSE 包含未知事件类型");
        }
        ReportStreamEvent event = parseStableEvent(frame.eventType, frame.data, expectedExecutionId);
        if (event.getSequence() != lastStableSequence[0] + 1) {
            throw new ReportStreamException("report_protocol_error", "Report 稳定阶段序号不连续或重复");
        }
        lastStableSequence[0] = event.getSequence();
        if (eventConsumer != null) eventConsumer.accept(event);
    }

    private ReportStreamEvent parseStableEvent(String streamType, String data, String expectedExecutionId) {
        JSONObject object = parseObject(data, "稳定阶段");
        ReportStreamEvent event = new ReportStreamEvent();
        event.setStreamEventType(streamType);
        event.setSchemaVersion(object.getString("schemaVersion"));
        event.setEventId(object.getString("eventId"));
        event.setExecutionId(object.getString("executionId"));
        event.setSequence(object.getInteger("sequence"));
        event.setPhase(object.getString("phase"));
        event.setStatus(object.getString("status"));
        event.setOccurredAt(parseInstant(object.getString("occurredAt"), "occurredAt"));
        event.setSummary(object.getString("summary"));
        event.setData(copyMap(object.getJSONObject("data")));
        if (!PROGRESS_SCHEMA.equals(event.getSchemaVersion())
                || !expectedExecutionId.equals(event.getExecutionId())
                || !StringUtils.hasText(event.getEventId())
                || event.getSequence() == null || event.getSequence() < 1
                || !ListContract.PHASES.contains(event.getPhase())
                || !ListContract.STATUSES.contains(event.getStatus())
                || !StringUtils.hasText(event.getSummary()) || event.getSummary().length() > 1200) {
            throw new ReportStreamException("report_protocol_error", "Report 稳定阶段不符合 report_progress_event.v1");
        }
        if ("artifact_created".equals(event.getPhase())) {
            Object artifact = event.getData().get("artifact");
            if (!(artifact instanceof Map<?, ?> values) || !ARTIFACT_SCHEMA.equals(String.valueOf(values.get("schemaVersion")))) {
                throw new ReportStreamException("report_protocol_error", "Report artifact_created 阶段缺少有效 Artifact descriptor");
            }
        }
        return event;
    }

    private ReportContentDelta parseDelta(String data, String expectedExecutionId) {
        JSONObject object = parseObject(data, "delta");
        ReportContentDelta delta = new ReportContentDelta();
        delta.setSchemaVersion(object.getString("schemaVersion"));
        delta.setExecutionId(object.getString("executionId"));
        delta.setOutputType(object.getString("outputType"));
        delta.setChunkIndex(object.getInteger("chunkIndex"));
        delta.setContent(object.getString("content"));
        if (!DELTA_SCHEMA.equals(delta.getSchemaVersion()) || !expectedExecutionId.equals(delta.getExecutionId())
                || !ListContract.OUTPUT_TYPES.contains(delta.getOutputType()) || delta.getChunkIndex() == null
                || delta.getChunkIndex() < 1 || !StringUtils.hasText(delta.getContent())
                || delta.getContent().length() > MAX_DELTA_CHARS) {
            throw new ReportStreamException("report_protocol_error", "Report delta 不符合 report_content_delta.v1");
        }
        return delta;
    }

    private ReportFinalResult parseFinalResult(String data, String expectedExecutionId) {
        ReportFinalResult result;
        try {
            result = JSON.parseObject(data, ReportFinalResult.class);
        } catch (RuntimeException exception) {
            throw new ReportStreamException("report_protocol_error", "Report 终态 JSON 无效", exception);
        }
        if (result == null || !RESULT_SCHEMA.equals(result.getSchemaVersion()) || !expectedExecutionId.equals(result.getExecutionId())
                || !StringUtils.hasText(result.getReportExecutionId()) || !ListContract.STATUSES.contains(result.getStatus())
                || !StringUtils.hasText(result.getSummary()) || !ListContract.OUTPUT_TYPES.contains(result.getOutputType())) {
            throw new ReportStreamException("report_protocol_error", "Report 终态不符合 report_result.v1");
        }
        if ("completed".equals(result.getStatus())) {
            Map<String, Object> artifact = result.getArtifact();
            if (artifact == null || !ARTIFACT_SCHEMA.equals(String.valueOf(artifact.get("schemaVersion")))) {
                throw new ReportStreamException("report_protocol_error", "成功的 Report 终态缺少有效 Artifact descriptor");
            }
        } else if (!StringUtils.hasText(result.getErrorCode())) {
            throw new ReportStreamException("report_protocol_error", "失败的 Report 终态缺少受控错误码");
        }
        if (result.getLimitations() == null) result.setLimitations(java.util.List.of());
        if (result.getMaterialStatistics() == null) result.setMaterialStatistics(Map.of());
        return result;
    }

    private JSONObject parseObject(String data, String label) {
        try {
            JSONObject object = JSON.parseObject(data);
            if (object == null) throw new ReportStreamException("report_protocol_error", "Report " + label + " 不是 JSON 对象");
            return object;
        } catch (ReportStreamException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReportStreamException("report_protocol_error", "Report " + label + " 不是合法 JSON", exception);
        }
    }

    private String readLimitedBody(InputStream body) throws IOException {
        if (body == null) return "";
        byte[] bytes = body.readNBytes(MAX_ERROR_RESPONSE_BYTES + 1);
        return new String(bytes, 0, Math.min(bytes.length, MAX_ERROR_RESPONSE_BYTES), StandardCharsets.UTF_8);
    }

    private String validationDetail(JSONArray violations) {
        if (violations == null || violations.isEmpty()) return null;
        JSONObject violation = violations.getJSONObject(0);
        String field = violation == null ? null : safeIdentifier(violation.getString("field"));
        String rule = violation == null ? null : safeIdentifier(violation.getString("rule"));
        return StringUtils.hasText(field) && StringUtils.hasText(rule) ? "字段 " + field + " 不满足 " + rule + " 约束" : null;
    }

    private String safeIdentifier(String value) {
        return StringUtils.hasText(value) && value.trim().matches("[A-Za-z0-9_.\\[\\]-]{1,120}") ? value.trim() : null;
    }

    private Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new ReportStreamException("report_protocol_error", "Report " + field + " 无效", exception);
        }
    }

    private URI resolveUri(String pythonService, String endpoint) {
        if (!StringUtils.hasText(pythonService) || !StringUtils.hasText(endpoint)) {
            throw new ReportStreamException("report_config_missing", "Report Python 服务地址或 endpoint 未配置");
        }
        try {
            return URI.create(pythonService.replaceAll("/+$", "") + (endpoint.startsWith("/") ? endpoint : "/" + endpoint));
        } catch (IllegalArgumentException exception) {
            throw new ReportStreamException("report_config_invalid", "Report Python 服务地址无效", exception);
        }
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static final class SseFrame {
        private String eventType;
        private String data = "";

        private void appendData(String value) { data = data.isEmpty() ? value : data + "\n" + value; }
        private boolean hasData() { return StringUtils.hasText(data); }
        private void clear() { eventType = null; data = ""; }
    }

    private static final class ListContract {
        private static final java.util.Set<String> PHASES = java.util.Set.of(
                "inputs_resolved", "generation_started", "artifact_created", "completed", "failed"
        );
        private static final java.util.Set<String> STATUSES = java.util.Set.of("running", "completed", "failed");
        private static final java.util.Set<String> OUTPUT_TYPES = java.util.Set.of("markdown", "html", "ppt");
    }
}
