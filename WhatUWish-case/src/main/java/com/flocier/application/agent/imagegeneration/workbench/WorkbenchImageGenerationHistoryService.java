package com.flocier.application.agent.imagegeneration.workbench;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IImageGenerationExecutionRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionSource;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/** 按工作台来源恢复图片创作历史，不读取 Runtime Replay 或 Agent Run。 */
@Service
@RequiredArgsConstructor
public class WorkbenchImageGenerationHistoryService {
    private static final int DEFAULT_PAGE_SIZE = 12;
    private static final int MAX_PAGE_SIZE = 50;

    private final IImageGenerationExecutionRepository executionRepository;
    private final IArtifactLedgerRepository artifactLedgerRepository;
    private final ArtifactContentAccessService artifactContentAccessService;

    public WorkbenchImageGenerationHistoryPage query(String sessionId, Integer pageNo, Integer pageSize) {
        if (StringUtils.isBlank(sessionId)) {
            throw new WorkbenchImageGenerationException("image_generation_history_invalid", "图片工作台缺少会话标识。");
        }
        int resolvedPageNo = pageNo == null ? 1 : pageNo;
        int resolvedPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (resolvedPageNo < 1 || resolvedPageSize < 1 || resolvedPageSize > MAX_PAGE_SIZE) {
            throw new WorkbenchImageGenerationException("image_generation_history_invalid", "图片工作台历史分页参数不符合约束。");
        }
        long total = executionRepository.countByExecutionSourceAndSession(
                ImageGenerationExecutionSource.WORKBENCH, sessionId);
        int offset = Math.toIntExact((long) (resolvedPageNo - 1) * resolvedPageSize);
        List<WorkbenchImageGenerationHistoryItem> items = executionRepository
                .findPageByExecutionSourceAndSession(
                        ImageGenerationExecutionSource.WORKBENCH, sessionId, offset, resolvedPageSize)
                .stream()
                .map(this::toItem)
                .toList();
        return WorkbenchImageGenerationHistoryPage.builder()
                .sessionId(sessionId)
                .pageNo(resolvedPageNo)
                .pageSize(resolvedPageSize)
                .total(total)
                .hasMore((long) offset + items.size() < total)
                .items(items)
                .build();
    }

    private WorkbenchImageGenerationHistoryItem toItem(ImageGenerationExecutionEntity execution) {
        return WorkbenchImageGenerationHistoryItem.builder()
                .executionId(execution.getImageGenerationExecutionId())
                .status(execution.getStatus())
                .summary(execution.getFinalSummary())
                .mode(execution.getGenerationMode())
                .size(execution.getRequestedSize())
                .requestedCount(execution.getRequestedOutputCount())
                .generatedCount(execution.getGeneratedOutputCount())
                .referenceCount(execution.getReferenceArtifactCount())
                .maskCount(execution.getMaskArtifactCount())
                .usedFallback(execution.getUsedFallback())
                .errorCode(execution.getErrorCode())
                .errorMessage(execution.getErrorMessage())
                .startedAt(execution.getStartedAt())
                .finishedAt(execution.getFinishedAt())
                .artifacts(outputArtifacts(execution.getImageGenerationExecutionId()))
                .build();
    }

    private List<WorkbenchImageArtifact> outputArtifacts(String executionId) {
        return artifactLedgerRepository.findByRunIdsIncludingDeleted(List.of(executionId)).stream()
                .filter(artifact -> "output".equalsIgnoreCase(artifact.getArtifactRole()))
                .map(artifact -> delivery(executionId, artifact))
                .toList();
    }

    private WorkbenchImageArtifact delivery(String executionId, ArtifactLedgerEntity artifact) {
        try {
            ArtifactDeliveryFile delivery = artifactContentAccessService.metadata(
                    executionId,
                    artifact.getArtifactId(),
                    com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessSubject.localTrustedSubject()
            );
            return WorkbenchImageArtifact.builder()
                    .artifactId(delivery.getArtifactId())
                    .name(delivery.getName())
                    .mimeType(delivery.getMimeType())
                    .size(delivery.getSize())
                    .accessState(delivery.getAccessState().name())
                    .preview(delivery.getPreview())
                    .previewSupported(delivery.isPreviewSupported())
                    .downloadSupported(delivery.isDownloadSupported())
                    .build();
        } catch (ArtifactContentAccessException exception) {
            return unavailable(artifact, exception.getState());
        }
    }

    private WorkbenchImageArtifact unavailable(ArtifactLedgerEntity artifact, ArtifactAccessState state) {
        return WorkbenchImageArtifact.builder()
                .artifactId(artifact.getArtifactId())
                .name(artifact.getArtifactName())
                .mimeType(artifact.getMimeType())
                .size(artifact.getFileSize())
                .accessState(state.name())
                .preview((ArtifactPreviewDescriptor) null)
                .previewSupported(false)
                .downloadSupported(false)
                .build();
    }
}
