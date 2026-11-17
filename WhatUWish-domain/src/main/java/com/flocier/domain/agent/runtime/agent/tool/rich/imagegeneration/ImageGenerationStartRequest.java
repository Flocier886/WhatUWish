package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import org.springframework.util.StringUtils;

/** Image Rich Tool 的受控启动请求；存储定位只在 {@link #toPythonPayload()} 私有载荷中存在。 */
@Value
@Builder
public class ImageGenerationStartRequest {
    String prompt;
    String mode;
    String outputFileName;
    String size;
    Integer requestedCount;
    String executionId;
    List<String> referenceArtifactIds;
    List<String> maskArtifactIds;
    List<ImageGenerationInputArtifactDescriptor> referenceArtifacts;
    List<ImageGenerationInputArtifactDescriptor> maskArtifacts;

    public static ImageGenerationStartRequest from(ToolInvokeRequest request, String executionId) {
        Map<String, Object> arguments = request == null || request.getArguments() == null ? Map.of() : request.getArguments();
        return fromArguments(arguments, executionId);
    }

    /** 工作台使用同一受控请求模型，但不需要构造 Tool Invocation。 */
    public static ImageGenerationStartRequest fromArguments(Map<String, Object> arguments, String executionId) {
        arguments = arguments == null ? Map.of() : arguments;
        String prompt = normalized(arguments.get("prompt"), 2400);
        if (!StringUtils.hasText(prompt)) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", "image_generation_tool 需要非空 prompt 参数");
        }
        String mode = normalized(arguments.get("mode"), 32);
        if (StringUtils.hasText(mode) && !List.of("images", "edits").contains(mode)) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", "mode 仅支持 images 或 edits");
        }
        List<String> references = artifactIds(arguments.get("referenceArtifactIds"), "referenceArtifactIds");
        List<String> masks = artifactIds(arguments.get("maskArtifactIds"), "maskArtifactIds");
        if (references.size() > 10 || masks.size() > 10 || masks.size() > references.size()) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", "图片参考或遮罩 Artifact 数量不符合约束");
        }
        if ("edits".equals(mode) && references.isEmpty()) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", "edits 模式至少需要一张参考图片");
        }
        int requestedCount = positiveInt(arguments.get("n"), 1, 1, 10, "n");
        String outputFileName = normalized(arguments.get("outputFileName"), 160);
        String size = normalized(arguments.get("size"), 32);
        if (!StringUtils.hasText(executionId)) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", "图片生成执行标识不能为空");
        }
        return builder().prompt(prompt).mode(mode).outputFileName(StringUtils.hasText(outputFileName) ? outputFileName : "图片生成结果")
                .size(size).requestedCount(requestedCount).executionId(executionId)
                .referenceArtifactIds(List.copyOf(references)).maskArtifactIds(List.copyOf(masks))
                .referenceArtifacts(List.of()).maskArtifacts(List.of()).build();
    }

    public ImageGenerationStartRequest withResolvedInputs(
            List<ImageGenerationInputArtifactDescriptor> resolvedReferences,
            List<ImageGenerationInputArtifactDescriptor> resolvedMasks
    ) {
        if (resolvedReferences == null || resolvedMasks == null || resolvedReferences.size() != referenceArtifactIds.size()
                || resolvedMasks.size() != maskArtifactIds.size()) {
            throw new ImageGenerationStreamException("image_generation_input_invalid", "图片输入 Artifact 解析结果不完整");
        }
        return builder().prompt(prompt).mode(mode).outputFileName(outputFileName).size(size).requestedCount(requestedCount)
                .executionId(executionId).referenceArtifactIds(referenceArtifactIds).maskArtifactIds(maskArtifactIds)
                .referenceArtifacts(List.copyOf(resolvedReferences)).maskArtifacts(List.copyOf(resolvedMasks)).build();
    }

    /** Java -> Python 私有协议，禁止被 Ledger、Live 或 observation 直接复用。 */
    public Map<String, Object> toPythonPayload() {
        if (referenceArtifacts == null || maskArtifacts == null) {
            throw new ImageGenerationStreamException("image_generation_input_invalid", "图片输入 Artifact 尚未授权解析");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", prompt);
        if (StringUtils.hasText(mode)) payload.put("mode", mode);
        payload.put("referenceArtifacts", privateDescriptors(referenceArtifacts));
        payload.put("maskArtifacts", privateDescriptors(maskArtifacts));
        payload.put("outputFileName", outputFileName);
        if (StringUtils.hasText(size)) payload.put("size", size);
        payload.put("n", requestedCount);
        payload.put("executionId", executionId);
        return payload;
    }

    private static List<Map<String, Object>> privateDescriptors(List<ImageGenerationInputArtifactDescriptor> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ImageGenerationInputArtifactDescriptor item : values) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("artifactId", item.getArtifactId()); value.put("fileName", item.getFileName());
            value.put("mimeType", item.getMimeType()); value.put("fileSize", item.getFileSize());
            value.put("storageType", item.getStorageType()); value.put("storageKey", item.getStorageKey());
            value.put("role", item.getRole()); result.add(value);
        }
        return result;
    }

    private static String normalized(Object value, int maximum) {
        String text = value == null ? "" : String.valueOf(value).trim().replaceAll("\\s+", " ");
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private static List<String> artifactIds(Object value, String field) {
        if (value == null) return List.of();
        if (!(value instanceof Iterable<?> values)) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", field + " 必须是 Artifact ID 数组");
        }
        List<String> ids = new ArrayList<>();
        for (Object item : values) {
            String id = normalized(item, 80);
            if (!StringUtils.hasText(id)) throw new ImageGenerationStreamException("image_generation_request_invalid", field + " 包含无效标识");
            ids.add(id);
        }
        if (ids.size() != ids.stream().distinct().count()) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", field + " 不允许重复 Artifact");
        }
        return ids;
    }

    private static int positiveInt(Object value, int fallback, int minimum, int maximum, String field) {
        if (value == null) return fallback;
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", field + " 超出允许范围");
        }
    }
}
