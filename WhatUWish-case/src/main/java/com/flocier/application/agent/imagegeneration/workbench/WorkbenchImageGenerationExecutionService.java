package com.flocier.application.agent.imagegeneration.workbench;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationExecutionKernel;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationFinalResult;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationInputArtifactDescriptor;
import com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationStartRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import com.flocier.domain.ledger.adapter.repository.IImageGenerationExecutionRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionInputArtifactEntity;
import com.flocier.domain.ledger.model.entity.ImageGenerationExecutionSource;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 图片工作台的直接执行入口。
 *
 * <p>工作台复用图片执行内核和 Artifact 服务，但不创建 Agent Run、Tool Invocation、Runtime Event 或 Replay。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkbenchImageGenerationExecutionService {

    public static final String SOURCE_NAME = "image_generation_workbench";
    private static final long MAX_SINGLE_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_INPUT_COUNT = 10;
    private static final int SUMMARY_LIMIT = 1200;

    private final ArtifactContentWriter artifactContentWriter;
    private final ArtifactContentAccessService artifactContentAccessService;
    private final LedgerRecordingService ledgerRecordingService;
    private final IImageGenerationExecutionRepository executionRepository;
    private final ImageGenerationExecutionKernel executionKernel;
    private final ReactorConfig reactorConfig;

    @Value("${artifact.content.write.storage-type:local_artifact_storage}")
    private String writeStorageType;

    @Value("${artifact.content.write.prefix:}")
    private String writePrefix;

    public WorkbenchImageGenerationResult execute(WorkbenchImageGenerationCommand command) {
        validateCommand(command);
        Instant startedAt = Instant.now();
        String executionId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        ImageGenerationExecutionEntity execution = newExecution(executionId, requestId, command, startedAt);
        executionRepository.save(execution);

        List<ArtifactDeliveryFile> outputArtifacts = new ArrayList<>();
        try {
            UploadedInputs inputs = persistInputs(execution, command, startedAt);
            executionRepository.saveInputArtifacts(inputs.relations());
            ImageGenerationStartRequest request = startRequest(command, executionId, inputs);
            PythonEndpoint endpoint = pythonEndpoint();
            ImageGenerationFinalResult result = executionKernel.execute(
                    endpoint.pythonService(),
                    endpoint.pythonEndpoint(),
                    request,
                    event -> registerOutputArtifact(execution, event, outputArtifacts)
            );
            if (result == null || !"completed".equals(result.getStatus()) || outputArtifacts.isEmpty()) {
                throw new WorkbenchImageGenerationException(
                        result == null ? "image_generation_protocol_error" : firstNonBlank(result.getErrorCode(), "image_generation_failed"),
                        result == null ? "图片生成没有返回可信终态。" : result.getSummary()
                );
            }
            finish(execution, "completed", result, null, null, outputArtifacts.size());
            return result(execution, outputArtifacts);
        } catch (WorkbenchImageGenerationException exception) {
            finish(execution, "failed", null, exception.getErrorCode(), exception.getMessage(), outputArtifacts.size());
            return failure(execution, outputArtifacts, exception.getErrorCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("image workbench execution failed, executionId={}, sessionId={}",
                    executionId, command.getSessionId(), exception);
            finish(execution, "failed", null, "image_generation_execution_error", "图片工作台生成未能完成可信交付。", outputArtifacts.size());
            return failure(execution, outputArtifacts, "image_generation_execution_error", "图片工作台生成未能完成可信交付。");
        }
    }

    private ImageGenerationExecutionEntity newExecution(
            String executionId,
            String requestId,
            WorkbenchImageGenerationCommand command,
            Instant startedAt
    ) {
        List<WorkbenchImageInput> references = inputs(command.getReferences());
        List<WorkbenchImageInput> masks = inputs(command.getMasks());
        return ImageGenerationExecutionEntity.builder()
                .imageGenerationExecutionId(executionId)
                .executionSource(ImageGenerationExecutionSource.WORKBENCH)
                .sessionId(command.getSessionId())
                .requestId(requestId)
                .generationMode(resolveMode(command.getMode(), references))
                .requestedSize(normalize(command.getSize(), 32))
                .requestedOutputCount(command.getCount() == null ? 1 : command.getCount())
                .generatedOutputCount(0)
                .referenceArtifactCount(references.size())
                .maskArtifactCount(masks.size())
                .usedFallback(false)
                .status("running")
                .startedAt(startedAt)
                .versionNo(0L)
                .createdAt(startedAt)
                .updatedAt(startedAt)
                .build();
    }

    private UploadedInputs persistInputs(
            ImageGenerationExecutionEntity execution,
            WorkbenchImageGenerationCommand command,
            Instant createdAt
    ) {
        List<ImageGenerationInputArtifactDescriptor> references = new ArrayList<>();
        List<ImageGenerationInputArtifactDescriptor> masks = new ArrayList<>();
        List<ImageGenerationExecutionInputArtifactEntity> relations = new ArrayList<>();
        persistRole(execution, inputs(command.getReferences()), "reference", createdAt, references, relations);
        persistRole(execution, inputs(command.getMasks()), "mask", createdAt, masks, relations);
        return new UploadedInputs(List.copyOf(references), List.copyOf(masks), List.copyOf(relations));
    }

    private void persistRole(
            ImageGenerationExecutionEntity execution,
            List<WorkbenchImageInput> inputs,
            String role,
            Instant createdAt,
            List<ImageGenerationInputArtifactDescriptor> descriptors,
            List<ImageGenerationExecutionInputArtifactEntity> relations
    ) {
        for (int index = 0; index < inputs.size(); index++) {
            WorkbenchImageInput input = inputs.get(index);
            String artifactId = UUID.randomUUID().toString();
            String mimeType = detectedMime(input.getContent());
            String storageKey = new ArtifactObjectKeyBuilder(writePrefix).build(artifactId);
            ArtifactStoredContent stored = artifactContentWriter.write(ArtifactContentWriteRequest.builder()
                    .storageType(writeStorageType)
                    .storageKey(storageKey)
                    .mimeType(mimeType)
                    .content(input.getContent())
                    .build());
            if (stored == null || stored.getSize() != input.getContent().length) {
                throw new WorkbenchImageGenerationException("image_generation_input_store_failed", "图片输入存储确认失败。");
            }
            String fileName = safeFileName(input.getFileName(), mimeType, index);
            ArtifactLedgerEntity artifact = ArtifactLedgerEntity.builder()
                    .artifactId(artifactId)
                    .runId(execution.getImageGenerationExecutionId())
                    .sessionId(execution.getSessionId())
                    .requestId(execution.getRequestId())
                    .traceId(execution.getImageGenerationExecutionId())
                    .artifactRole("input")
                    .visibility("internal")
                    .sourceType("user_upload")
                    .sourceId(SOURCE_NAME + ":" + role + ":" + index)
                    .sourceName(SOURCE_NAME)
                    .artifactType("image")
                    .artifactName(fileName)
                    .fileName(fileName)
                    .storageType(writeStorageType)
                    .storageKey(storageKey)
                    .storageRef("local_artifact_storage".equals(writeStorageType) ? storageKey : null)
                    .mimeType(mimeType)
                    .size(stored.getSize())
                    .fileSize(stored.getSize())
                    .fileHash(sha256(input.getContent()))
                    .deleted(0)
                    .idempotencyKey("workbench-input:" + execution.getImageGenerationExecutionId() + ":" + role + ":" + index)
                    .createdAt(createdAt)
                    .updatedAt(createdAt)
                    .ext(Map.of("inputRole", role, "inputOrder", index, "uploadChannel", SOURCE_NAME))
                    .build();
            if (ledgerRecordingService.recordArtifact(artifact) == null) {
                throw new WorkbenchImageGenerationException("image_generation_input_record_failed", "图片输入 Artifact 登记失败。");
            }
            descriptors.add(ImageGenerationInputArtifactDescriptor.builder()
                    .artifactId(artifactId)
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .fileSize(stored.getSize())
                    .storageType(writeStorageType)
                    .storageKey(storageKey)
                    .role(role)
                    .build());
            relations.add(ImageGenerationExecutionInputArtifactEntity.builder()
                    .imageGenerationExecutionId(execution.getImageGenerationExecutionId())
                    .artifactId(artifactId)
                    .inputRole(role)
                    .inputOrder(index)
                    .createdAt(createdAt)
                    .build());
        }
    }

    private ImageGenerationStartRequest startRequest(
            WorkbenchImageGenerationCommand command,
            String executionId,
            UploadedInputs inputs
    ) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("prompt", command.getPrompt());
        arguments.put("mode", resolveMode(command.getMode(), inputs.references()));
        arguments.put("referenceArtifactIds", inputs.references().stream().map(ImageGenerationInputArtifactDescriptor::getArtifactId).toList());
        arguments.put("maskArtifactIds", inputs.masks().stream().map(ImageGenerationInputArtifactDescriptor::getArtifactId).toList());
        arguments.put("outputFileName", command.getOutputFileName());
        arguments.put("size", command.getSize());
        arguments.put("n", command.getCount());
        return ImageGenerationStartRequest.fromArguments(arguments, executionId)
                .withResolvedInputs(inputs.references(), inputs.masks());
    }

    private void registerOutputArtifact(
            ImageGenerationExecutionEntity execution,
            com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration.ImageGenerationStreamEvent event,
            List<ArtifactDeliveryFile> outputs
    ) {
        if (event == null || !"artifact_created".equals(event.getPhase())) {
            return;
        }
        Object raw = event.getData() == null ? null : event.getData().get("artifact");
        if (!(raw instanceof Map<?, ?> descriptor)) {
            throw new WorkbenchImageGenerationException("image_generation_artifact_invalid", "图片生成未返回有效输出 Artifact。");
        }
        String artifactId = text(descriptor, "artifactId");
        String storageType = text(descriptor, "storageType");
        String storageKey = text(descriptor, "storageKey");
        String storageRef = text(descriptor, "storageRef");
        String mimeType = text(descriptor, "mimeType");
        Long fileSize = longValue(descriptor.get("fileSize"));
        if (StringUtils.isAnyBlank(artifactId, storageType, storageKey, storageRef, mimeType)
                || fileSize == null || fileSize < 0) {
            throw new WorkbenchImageGenerationException("image_generation_artifact_invalid", "图片生成输出 Artifact 描述不完整。");
        }
        Instant now = Instant.now();
        ArtifactLedgerEntity artifact = ArtifactLedgerEntity.builder()
                .artifactId(artifactId)
                .runId(execution.getImageGenerationExecutionId())
                .sessionId(execution.getSessionId())
                .requestId(execution.getRequestId())
                .traceId(execution.getImageGenerationExecutionId())
                .artifactRole("output")
                .visibility("visible")
                .sourceType(firstNonBlank(text(descriptor, "sourceType"), "python_artifact"))
                .sourceId(artifactId)
                .sourceName(firstNonBlank(text(descriptor, "sourceName"), SOURCE_NAME))
                .artifactType(firstNonBlank(text(descriptor, "artifactType"), "image"))
                .artifactName(firstNonBlank(text(descriptor, "name"), text(descriptor, "fileName")))
                .fileName(firstNonBlank(text(descriptor, "fileName"), text(descriptor, "name")))
                .storageType(storageType)
                .storageKey(storageKey)
                .storageRef(storageRef)
                .contentSummary(limit(text(descriptor, "contentSummary"), SUMMARY_LIMIT))
                .mimeType(mimeType)
                .size(fileSize)
                .fileSize(fileSize)
                .fileHash(text(descriptor, "fileHash"))
                .metadataJson(metadataJson(descriptor.get("metadata")))
                .deleted(0)
                .idempotencyKey("workbench-output:" + execution.getImageGenerationExecutionId() + ":" + artifactId)
                .createdAt(now)
                .updatedAt(now)
                .ext(Map.of("executionSource", ImageGenerationExecutionSource.WORKBENCH, "executionId", execution.getImageGenerationExecutionId()))
                .build();
        ArtifactLedgerEntity recorded = ledgerRecordingService.recordArtifact(artifact);
        if (recorded == null) {
            throw new WorkbenchImageGenerationException("image_generation_artifact_record_failed", "图片生成输出 Artifact 登记失败。");
        }
        ArtifactDeliveryFile delivery = artifactContentAccessService.metadata(
                execution.getImageGenerationExecutionId(),
                artifactId,
                com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessSubject.localTrustedSubject()
        );
        if (delivery.getAccessState() != ArtifactAccessState.AVAILABLE) {
            throw new WorkbenchImageGenerationException("image_generation_artifact_unavailable", "图片生成结果当前不可交付。");
        }
        outputs.add(delivery);
    }

    private void finish(
            ImageGenerationExecutionEntity execution,
            String status,
            ImageGenerationFinalResult result,
            String errorCode,
            String errorMessage,
            int outputCount
    ) {
        Instant now = Instant.now();
        execution.setStatus(status);
        execution.setPythonExecutionId(result == null ? execution.getPythonExecutionId() : result.getImageExecutionId());
        execution.setGeneratedOutputCount(outputCount);
        execution.setUsedFallback(result != null && Boolean.TRUE.equals(result.getUsedFallback()));
        execution.setFinalSummary(limit(result == null ? errorMessage : result.getSummary(), SUMMARY_LIMIT));
        execution.setErrorCode(errorCode);
        execution.setErrorMessage(limit(errorMessage, SUMMARY_LIMIT));
        execution.setFinishedAt(now);
        execution.setUpdatedAt(now);
        execution.setVersionNo((execution.getVersionNo() == null ? 0L : execution.getVersionNo()) + 1L);
        executionRepository.update(execution);
    }

    private WorkbenchImageGenerationResult result(
            ImageGenerationExecutionEntity execution,
            List<ArtifactDeliveryFile> outputArtifacts
    ) {
        return WorkbenchImageGenerationResult.builder()
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
                .artifacts(toArtifacts(outputArtifacts))
                .build();
    }

    private WorkbenchImageGenerationResult failure(
            ImageGenerationExecutionEntity execution,
            List<ArtifactDeliveryFile> outputArtifacts,
            String errorCode,
            String errorMessage
    ) {
        return WorkbenchImageGenerationResult.builder()
                .executionId(execution.getImageGenerationExecutionId())
                .status("failed")
                .summary(execution.getFinalSummary())
                .mode(execution.getGenerationMode())
                .size(execution.getRequestedSize())
                .requestedCount(execution.getRequestedOutputCount())
                .generatedCount(execution.getGeneratedOutputCount())
                .referenceCount(execution.getReferenceArtifactCount())
                .maskCount(execution.getMaskArtifactCount())
                .usedFallback(execution.getUsedFallback())
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .artifacts(toArtifacts(outputArtifacts))
                .build();
    }

    private List<WorkbenchImageArtifact> toArtifacts(List<ArtifactDeliveryFile> values) {
        return values.stream().map(value -> WorkbenchImageArtifact.builder()
                .artifactId(value.getArtifactId())
                .name(value.getName())
                .mimeType(value.getMimeType())
                .size(value.getSize())
                .accessState(value.getAccessState().name())
                .preview(value.getPreview())
                .previewSupported(value.isPreviewSupported())
                .downloadSupported(value.isDownloadSupported())
                .build()).toList();
    }

    private PythonEndpoint pythonEndpoint() {
        Map<String, Object> config = reactorConfig.getToolConfigMap().get("image_generation_tool");
        String pythonService = config == null ? null : text(config, "pythonService");
        String pythonEndpoint = config == null ? null : text(config, "pythonEndpoint");
        if (StringUtils.isAnyBlank(pythonService, pythonEndpoint)) {
            throw new WorkbenchImageGenerationException("image_generation_config_missing", "图片生成服务未完成受控配置。");
        }
        return new PythonEndpoint(pythonService, pythonEndpoint);
    }

    private void validateCommand(WorkbenchImageGenerationCommand command) {
        if (command == null || StringUtils.isAnyBlank(command.getSessionId(), command.getPrompt())) {
            throw new WorkbenchImageGenerationException("image_generation_request_invalid", "图片工作台缺少会话或生成描述。");
        }
        List<WorkbenchImageInput> references = inputs(command.getReferences());
        List<WorkbenchImageInput> masks = inputs(command.getMasks());
        if (references.size() > MAX_INPUT_COUNT || masks.size() > MAX_INPUT_COUNT || masks.size() > references.size()) {
            throw new WorkbenchImageGenerationException("image_generation_request_invalid", "参考图或遮罩图数量不符合约束。");
        }
        if ("edits".equals(command.getMode()) && references.isEmpty()) {
            throw new WorkbenchImageGenerationException("image_generation_request_invalid", "edits 模式至少需要一张参考图。");
        }
        long total = 0L;
        for (WorkbenchImageInput input : concat(references, masks)) {
            if (input == null || input.getContent() == null || input.getContent().length == 0 || input.getContent().length > MAX_SINGLE_BYTES) {
                throw new WorkbenchImageGenerationException("image_generation_request_invalid", "上传图片大小不符合约束。");
            }
            total += input.getContent().length;
            if (total > MAX_TOTAL_BYTES) {
                throw new WorkbenchImageGenerationException("image_generation_request_invalid", "上传图片总大小超过约束。");
            }
            String declared = normalizeMime(input.getDeclaredMimeType());
            String detected = detectedMime(input.getContent());
            if (StringUtils.isNotBlank(declared) && !declared.equals(detected)) {
                throw new WorkbenchImageGenerationException("image_generation_request_invalid", "上传图片类型与内容不一致。");
            }
        }
    }

    private String resolveMode(String mode, List<?> references) {
        return "images".equals(mode) || "edits".equals(mode) ? mode : references.isEmpty() ? "images" : "edits";
    }

    private List<WorkbenchImageInput> inputs(List<WorkbenchImageInput> values) {
        return values == null ? List.of() : values;
    }

    private List<WorkbenchImageInput> concat(List<WorkbenchImageInput> references, List<WorkbenchImageInput> masks) {
        List<WorkbenchImageInput> values = new ArrayList<>(references);
        values.addAll(masks);
        return values;
    }

    private String detectedMime(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) return "image/jpeg";
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        if (bytes.length >= 6 && (new String(bytes, 0, 6, StandardCharsets.US_ASCII).equals("GIF87a")
                || new String(bytes, 0, 6, StandardCharsets.US_ASCII).equals("GIF89a"))) return "image/gif";
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') return "image/bmp";
        throw new WorkbenchImageGenerationException("image_generation_request_invalid", "仅支持 PNG、JPEG、WEBP、GIF 或 BMP 栅格图片。");
    }

    private String safeFileName(String raw, String mimeType, int index) {
        String value = raw == null ? "" : raw.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        String extension = switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".bmp";
        };
        if (value.isBlank()) return "workbench-input-" + (index + 1) + extension;
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    private String normalizeMime(String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String normalize(String value, int maximum) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private String metadataJson(Object metadata) {
        return metadata instanceof Map<?, ?> values && !values.isEmpty() ? JSON.toJSONString(values) : null;
    }

    private String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        try {
            return value == null ? null : value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.isNotBlank(first) ? first : fallback;
    }

    private String limit(String value, int maximum) {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record UploadedInputs(
            List<ImageGenerationInputArtifactDescriptor> references,
            List<ImageGenerationInputArtifactDescriptor> masks,
            List<ImageGenerationExecutionInputArtifactEntity> relations
    ) { }

    private record PythonEndpoint(String pythonService, String pythonEndpoint) { }
}
