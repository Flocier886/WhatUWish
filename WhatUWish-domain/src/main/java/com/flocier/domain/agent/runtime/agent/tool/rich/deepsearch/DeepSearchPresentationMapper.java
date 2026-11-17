package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将 DeepSearch Python SSE 的 presentation 投影校验为可持久化、可展示的固定契约。 */
@Component
public class DeepSearchPresentationMapper {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_SOURCES = 8;
    private static final int MAX_EVIDENCE = 12;
    private static final int MAX_TITLE_CHARS = 240;
    private static final int MAX_SNIPPET_CHARS = 360;
    private static final int MAX_REASON_CHARS = 240;
    private static final int MAX_CLAIM_CHARS = 400;
    private static final int MAX_QUOTE_CHARS = 240;
    private static final int MAX_SUMMARY_CHARS = 900;

    private static final Set<String> PRESENTATION_KINDS = Set.of(
            "deep_search.search_results.v1",
            "deep_search.source_selection.v1",
            "deep_search.fetch_results.v1",
            "deep_search.evidence_summary.v1",
            "deep_search.artifact.v1",
            "deep_search.completed.v1"
    );

    public DeepSearchPresentation map(DeepSearchStreamEvent event) {
        Map<String, Object> presentation = event.getPresentation();
        if (presentation == null || presentation.isEmpty()) {
            return new DeepSearchPresentation(
                    "deep_search.progress.v1",
                    "1",
                    Map.of("stage", event.getPhase(), "status", event.getStatus())
            );
        }

        String kind = string(presentation.get("kind"), 120);
        Integer schemaVersion = integer(presentation.get("schemaVersion"));
        Map<String, Object> data = object(presentation.get("data"));
        if (!PRESENTATION_KINDS.contains(kind) || schemaVersion == null || schemaVersion != SCHEMA_VERSION) {
            throw new DeepSearchStreamException(
                    "deep_search_presentation_invalid",
                    "DeepSearch 阶段展示数据不符合受控协议"
            );
        }

        return switch (kind) {
            case "deep_search.search_results.v1" -> new DeepSearchPresentation(kind, "1", searchResults(data));
            case "deep_search.source_selection.v1" -> new DeepSearchPresentation(kind, "1", sourceSelection(data));
            case "deep_search.fetch_results.v1" -> new DeepSearchPresentation(kind, "1", fetchResults(data));
            case "deep_search.evidence_summary.v1" -> new DeepSearchPresentation(kind, "1", evidenceSummary(data));
            case "deep_search.artifact.v1" -> new DeepSearchPresentation(kind, "1", artifact(data));
            case "deep_search.completed.v1" -> new DeepSearchPresentation(kind, "1", completed(data));
            default -> throw new DeepSearchStreamException(
                    "deep_search_presentation_invalid",
                    "DeepSearch 阶段展示类型不受支持"
            );
        };
    }

    /**
     * 历史回放重新校验已持久化的展示投影，绝不直接向前端透传 payload_json。
     */
    public DeepSearchPresentation restore(String kind, String schemaVersion, Map<String, Object> data) {
        if ("deep_search.progress.v1".equals(kind) && "1".equals(schemaVersion)) {
            Map<String, Object> progress = new LinkedHashMap<>();
            putIfPresent(progress, "stage", string(data.get("stage"), 80));
            putIfPresent(progress, "status", string(data.get("status"), 80));
            return new DeepSearchPresentation(kind, schemaVersion, progress);
        }
        if (!PRESENTATION_KINDS.contains(kind) || !"1".equals(schemaVersion)) {
            throw new DeepSearchStreamException(
                    "deep_search_presentation_invalid",
                    "DeepSearch 历史展示数据不符合受控协议"
            );
        }
        return switch (kind) {
            case "deep_search.search_results.v1" -> new DeepSearchPresentation(kind, "1", searchResults(data));
            case "deep_search.source_selection.v1" -> new DeepSearchPresentation(kind, "1", sourceSelection(data));
            case "deep_search.fetch_results.v1" -> new DeepSearchPresentation(kind, "1", fetchResults(data));
            case "deep_search.evidence_summary.v1" -> new DeepSearchPresentation(kind, "1", evidenceSummary(data));
            case "deep_search.artifact.v1" -> new DeepSearchPresentation(kind, "1", artifact(data));
            case "deep_search.completed.v1" -> new DeepSearchPresentation(kind, "1", completed(data));
            default -> throw new DeepSearchStreamException(
                    "deep_search_presentation_invalid",
                    "DeepSearch 历史展示类型不受支持"
            );
        };
    }

    private Map<String, Object> searchResults(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iteration", nonNegativeInteger(data.get("iteration")));
        result.put("queries", strings(data.get("queries"), MAX_SOURCES, MAX_SNIPPET_CHARS));
        result.put("sources", sources(data.get("sources"), false));
        return result;
    }

    private Map<String, Object> sourceSelection(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iteration", nonNegativeInteger(data.get("iteration")));
        result.put("usedFallback", Boolean.TRUE.equals(data.get("usedFallback")));
        result.put("sources", selectedSources(data.get("sources")));
        return result;
    }

    private Map<String, Object> fetchResults(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iteration", nonNegativeInteger(data.get("iteration")));
        putIfPresent(result, "fallbackReason", string(data.get("fallbackReason"), MAX_REASON_CHARS));
        result.put("sources", fetchedSources(data.get("sources")));
        return result;
    }

    private Map<String, Object> evidenceSummary(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iteration", nonNegativeInteger(data.get("iteration")));
        result.put("newEvidenceCount", nonNegativeInteger(data.get("newEvidenceCount")));
        result.put("evidenceCount", nonNegativeInteger(data.get("evidenceCount")));
        result.put("evidence", evidence(data.get("evidence")));
        return result;
    }

    private Map<String, Object> artifact(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        required(result, "artifactId", data.get("artifactId"), 128);
        required(result, "name", data.get("name"), MAX_TITLE_CHARS);
        required(result, "artifactType", data.get("artifactType"), 120);
        required(result, "mimeType", data.get("mimeType"), 120);
        putIfPresent(result, "summary", string(data.get("summary"), MAX_SUMMARY_CHARS));
        result.put("fileSize", nonNegativeLong(data.get("fileSize")));
        return result;
    }

    private Map<String, Object> completed(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        required(result, "summary", data.get("summary"), MAX_SUMMARY_CHARS);
        result.put("evidenceCount", nonNegativeInteger(data.get("evidenceCount")));
        putIfPresent(result, "researchOutcome", string(data.get("researchOutcome"), 80));
        putIfPresent(result, "stopReason", string(data.get("stopReason"), MAX_REASON_CHARS));
        result.put("limitations", strings(data.get("limitations"), 3, MAX_REASON_CHARS));
        return result;
    }

    private List<Map<String, Object>> sources(Object value, boolean includeStatus) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : objects(value, MAX_SOURCES)) {
            Map<String, Object> item = sourceIdentity(source);
            putIfPresent(item, "snippet", string(source.get("snippet"), MAX_SNIPPET_CHARS));
            if (includeStatus) {
                putIfPresent(item, "status", string(source.get("status"), 80));
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> selectedSources(Object value) {
        List<Map<String, Object>> result = sources(value, false);
        List<Map<String, Object>> sourceData = objects(value, MAX_SOURCES);
        for (int index = 0; index < result.size(); index++) {
            putIfPresent(result.get(index), "reason", string(sourceData.get(index).get("reason"), MAX_REASON_CHARS));
            String candidateSourceId = string(sourceData.get(index).get("candidateSourceId"), 96);
            if (StringUtils.isBlank(candidateSourceId)) {
                candidateSourceId = string(sourceData.get(index).get("sourceId"), 96);
            }
            putIfPresent(result.get(index), "candidateSourceId", candidateSourceId);
        }
        return result;
    }

    private List<Map<String, Object>> fetchedSources(Object value) {
        List<Map<String, Object>> result = sources(value, true);
        List<Map<String, Object>> sourceData = objects(value, MAX_SOURCES);
        for (int index = 0; index < result.size(); index++) {
            Map<String, Object> item = result.get(index);
            Map<String, Object> source = sourceData.get(index);
            item.put("contentLength", nonNegativeInteger(source.get("contentLength")));
            item.put("truncated", Boolean.TRUE.equals(source.get("truncated")));
            putIfPresent(item, "errorCode", string(source.get("errorCode"), 120));
        }
        return result;
    }

    private List<Map<String, Object>> evidence(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : objects(value, MAX_EVIDENCE)) {
            Map<String, Object> projection = new LinkedHashMap<>();
            required(projection, "sourceId", item.get("sourceId"), 96);
            putIfPresent(projection, "title", string(item.get("title"), MAX_TITLE_CHARS));
            putIfPresent(projection, "url", publicUrl(item.get("url")));
            required(projection, "claim", item.get("claim"), MAX_CLAIM_CHARS);
            required(projection, "quotePreview", item.get("quotePreview"), MAX_QUOTE_CHARS);
            required(projection, "verification", item.get("verification"), 80);
            result.add(projection);
        }
        return result;
    }

    private Map<String, Object> sourceIdentity(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        required(result, "sourceId", source.get("sourceId"), 96);
        required(result, "title", source.get("title"), MAX_TITLE_CHARS);
        required(result, "url", publicUrl(source.get("url")), 2048);
        required(result, "domain", source.get("domain"), 160);
        required(result, "provider", source.get("provider"), 80);
        return result;
    }

    private List<Map<String, Object>> objects(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (result.size() >= limit) {
                break;
            }
            result.add(object(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new DeepSearchStreamException(
                    "deep_search_presentation_invalid",
                    "DeepSearch 展示对象必须是 JSON object"
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<String> strings(Object value, int itemLimit, int charLimit) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (result.size() >= itemLimit) {
                break;
            }
            String text = string(item, charLimit);
            if (StringUtils.isNotBlank(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private void required(Map<String, Object> target, String field, Object value, int limit) {
        String text = string(value, limit);
        if (StringUtils.isBlank(text)) {
            throw new DeepSearchStreamException(
                    "deep_search_presentation_invalid",
                    "DeepSearch 展示数据缺少必填字段：" + field
            );
        }
        target.put(field, text);
    }

    private void putIfPresent(Map<String, Object> target, String field, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(field, value);
        }
    }

    private String publicUrl(Object value) {
        String url = string(value, 2048);
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.isNotBlank(uri.getHost())) {
                return url;
            }
        } catch (IllegalArgumentException ignored) {
            // 无效 URL 不会作为展示事实持久化。
        }
        return null;
    }

    private String string(Object value, int limit) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int nonNegativeInteger(Object value) {
        Integer number = integer(value);
        return number == null ? 0 : Math.max(number, 0);
    }

    private long nonNegativeLong(Object value) {
        if (value instanceof Number number) {
            return Math.max(number.longValue(), 0L);
        }
        try {
            return value == null ? 0L : Math.max(Long.parseLong(String.valueOf(value)), 0L);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public record DeepSearchPresentation(
            String kind,
            String schemaVersion,
            Map<String, Object> data
    ) {
    }
}
