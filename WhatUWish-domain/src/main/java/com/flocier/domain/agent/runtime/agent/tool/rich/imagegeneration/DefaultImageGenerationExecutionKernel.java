package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 图片执行内核实现：共享私有流消费与成功终态的 Artifact 身份校验。 */
@Component
public class DefaultImageGenerationExecutionKernel implements ImageGenerationExecutionKernel {

    private final ImageGenerationStreamClient streamClient;

    public DefaultImageGenerationExecutionKernel(ImageGenerationStreamClient streamClient) {
        this.streamClient = streamClient;
    }

    @Override
    public ImageGenerationFinalResult execute(
            String pythonService,
            String pythonEndpoint,
            ImageGenerationStartRequest request,
            Consumer<ImageGenerationStreamEvent> eventConsumer
    ) {
        if (request == null) {
            throw new ImageGenerationStreamException("image_generation_request_invalid", "图片生成启动请求不能为空");
        }
        List<String> streamedArtifactIds = new ArrayList<>();
        ImageGenerationFinalResult result = streamClient.stream(
                pythonService,
                pythonEndpoint,
                request,
                event -> {
                    collectArtifactId(event, streamedArtifactIds);
                    if (eventConsumer != null) {
                        eventConsumer.accept(event);
                    }
                }
        );
        validateCompletedResult(result, streamedArtifactIds);
        return result;
    }

    private void collectArtifactId(ImageGenerationStreamEvent event, List<String> target) {
        if (event == null || !"artifact_created".equals(event.getPhase())) {
            return;
        }
        Object rawArtifact = event.getData() == null ? null : event.getData().get("artifact");
        if (!(rawArtifact instanceof Map<?, ?> artifact) || !StringUtils.hasText(value(artifact, "artifactId"))) {
            throw new ImageGenerationStreamException(
                    "image_generation_contract_invalid",
                    "图片生成 artifact_created 阶段缺少受控 Artifact 标识"
            );
        }
        target.add(value(artifact, "artifactId"));
    }

    private void validateCompletedResult(ImageGenerationFinalResult result, List<String> streamedArtifactIds) {
        if (result == null || !"completed".equals(result.getStatus())) {
            return;
        }
        List<String> finalArtifactIds = artifactIds(result);
        if (finalArtifactIds.isEmpty() || !finalArtifactIds.equals(streamedArtifactIds)) {
            throw new ImageGenerationStreamException(
                    "image_generation_contract_invalid",
                    "图片生成成功终态未对应全部已确认的 Artifact 阶段"
            );
        }
    }

    private List<String> artifactIds(ImageGenerationFinalResult result) {
        if (result.getArtifacts() == null || result.getArtifacts().isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> artifact : result.getArtifacts()) {
            String artifactId = artifact == null ? null : value(artifact, "artifactId");
            if (!StringUtils.hasText(artifactId) || ids.contains(artifactId)) {
                return List.of();
            }
            ids.add(artifactId);
        }
        return List.copyOf(ids);
    }

    private String value(Map<?, ?> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
