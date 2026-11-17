package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/** 图片输入的授权/大小/MIME/定位解析边界；Python 不自行枚举文件或读取任意路径。 */
@Service
@RequiredArgsConstructor
public class ImageGenerationInputArtifactResolver {

    private static final long MAX_SINGLE_ARTIFACT_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_TOTAL_ARTIFACT_BYTES = 50L * 1024L * 1024L;
    private static final List<String> IMAGE_MIME_TYPES = List.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp"
    );

    private final IArtifactLedgerRepository artifactLedgerRepository;

    public ResolvedInputs resolve(AgentContext context, ImageGenerationStartRequest request) {
        if (context == null || request == null || StringUtils.isBlank(context.getSessionId())) {
            throw new ImageGenerationStreamException("image_generation_input_invalid", "图片输入缺少当前运行上下文");
        }
        Object runIdAttribute = context.getAttribute("runId");
        String runId = runIdAttribute == null ? null : String.valueOf(runIdAttribute);
        if (StringUtils.isBlank(runId)) {
            throw new ImageGenerationStreamException("image_generation_input_invalid", "图片输入缺少当前 Run 标识");
        }
        long[] totalBytes = {0L};
        List<ImageGenerationInputArtifactDescriptor> references = resolveRole(
                context, runId, request.getReferenceArtifactIds(), "reference", totalBytes
        );
        List<ImageGenerationInputArtifactDescriptor> masks = resolveRole(
                context, runId, request.getMaskArtifactIds(), "mask", totalBytes
        );
        return new ResolvedInputs(references, masks);
    }

    private List<ImageGenerationInputArtifactDescriptor> resolveRole(
            AgentContext context, String runId, List<String> artifactIds, String role, long[] totalBytes
    ) {
        List<ImageGenerationInputArtifactDescriptor> resolved = new ArrayList<>();
        for (String artifactId : artifactIds) {
            ArtifactLedgerEntity artifact = artifactLedgerRepository.findByArtifactId(artifactId)
                    .orElseThrow(() -> new ImageGenerationStreamException(
                            "image_generation_input_not_found", "指定的图片 Artifact 不存在或不可访问"
                    ));
            if (!runId.equals(artifact.getRunId()) || !context.getSessionId().equals(artifact.getSessionId())
                    || Integer.valueOf(1).equals(artifact.getDeleted()) || !isAuthorizedImageInput(artifact)
                    || StringUtils.isAnyBlank(artifact.getStorageType(), artifact.getStorageKey())) {
                throw new ImageGenerationStreamException("image_generation_input_forbidden", "指定图片 Artifact 不属于当前可访问运行");
            }
            Long size = artifact.getFileSize() == null ? artifact.getSize() : artifact.getFileSize();
            if (size == null || size <= 0 || size > MAX_SINGLE_ARTIFACT_BYTES || !isSupportedImage(artifact)) {
                throw new ImageGenerationStreamException("image_generation_input_unsupported", "输入 Artifact 不是受支持的图片文件");
            }
            totalBytes[0] += size;
            if (totalBytes[0] > MAX_TOTAL_ARTIFACT_BYTES) {
                throw new ImageGenerationStreamException("image_generation_input_budget_exceeded", "图片输入总大小超过读取预算");
            }
            resolved.add(ImageGenerationInputArtifactDescriptor.builder()
                    .artifactId(artifact.getArtifactId())
                    .fileName(StringUtils.defaultIfBlank(artifact.getFileName(), artifact.getArtifactName()))
                    .mimeType(normalizedMime(artifact.getMimeType()))
                    .fileSize(size).storageType(artifact.getStorageType()).storageKey(artifact.getStorageKey()).role(role)
                    .build());
        }
        return List.copyOf(resolved);
    }

    private boolean isSupportedImage(ArtifactLedgerEntity artifact) {
        return IMAGE_MIME_TYPES.contains(normalizedMime(artifact.getMimeType()));
    }

    /**
     * Agent 产生的图片仍必须是 visible output；工作台上传的图片在当前 Run 内仅作为受控 input 使用，
     * 不进入普通交付区，也不能借此放开任意 internal Artifact。
     */
    private boolean isAuthorizedImageInput(ArtifactLedgerEntity artifact) {
        boolean visibleOutput = "visible".equalsIgnoreCase(artifact.getVisibility())
                && "output".equalsIgnoreCase(artifact.getArtifactRole());
        boolean workbenchUpload = "internal".equalsIgnoreCase(artifact.getVisibility())
                && "input".equalsIgnoreCase(artifact.getArtifactRole())
                && "user_upload".equalsIgnoreCase(artifact.getSourceType())
                && "image_generation_workbench".equalsIgnoreCase(artifact.getSourceName());
        return visibleOutput || workbenchUpload;
    }

    private String normalizedMime(String mimeType) {
        return StringUtils.defaultString(mimeType).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    public record ResolvedInputs(
            List<ImageGenerationInputArtifactDescriptor> references,
            List<ImageGenerationInputArtifactDescriptor> masks
    ) { }
}
