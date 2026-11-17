package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import com.alibaba.fastjson.JSON;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 与 Python 同规则计算研究范围指纹，仅用于当前 Run 内的 DeepSearch 语义防重。 */
final class DeepSearchScopeFingerprint {

    private DeepSearchScopeFingerprint() {
    }

    static String fromPayload(Map<String, Object> payload) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("language", normalize(payload.get("language")));
        normalized.put("preferredDomains", normalizeList(payload.get("preferredDomains"), true));
        normalized.put("queries", normalizeList(payload.get("queries"), false));
        normalized.put("questions", normalizeList(payload.get("questions"), false));
        normalized.put("researchDepth", normalize(payload.get("researchDepth")));
        normalized.put("topic", normalize(payload.get("topic")));
        return "sha256:" + sha256(JSON.toJSONString(normalized));
    }

    private static List<String> normalizeList(Object value, boolean sorted) {
        List<String> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) values.add(normalize(item));
        }
        if (sorted) values.sort(Comparator.naturalOrder());
        return values;
    }

    private static String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : bytes) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
