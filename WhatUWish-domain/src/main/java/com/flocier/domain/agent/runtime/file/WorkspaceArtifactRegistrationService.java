package com.flocier.domain.agent.runtime.file;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactObjectKeyBuilder;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewPolicy;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IToolInvocationLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.service.LedgerRecordingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * 工作区产物显式登记入口，参考 master WorkspaceFileRegistration。
 * Agent 写入 outputs/ 只形成临时文件；调用 workspace_register 并通过调用身份校验后才进入 Artifact Ledger。
 */
@Service
@Slf4j
public class WorkspaceArtifactRegistrationService {
    @Value("${conversation.files.workspace-max-file-bytes:33554432}")
    private long maxBytes = 32L * 1024 * 1024;
    private final RunWorkspaceService workspace;
    private final ArtifactContentWriter writer;
    private final ArtifactContentRemover remover;
    private final IArtifactLedgerRepository artifacts;
    private final IToolInvocationLedgerRepository invocations;
    private final LedgerRecordingService ledger;
    private final RuntimeEventMapper events;
    private final RuntimeEventPublisher publisher;
    private final ArtifactObjectKeyBuilder keys;
    private final String storageType;

    public WorkspaceArtifactRegistrationService(RunWorkspaceService workspace, ArtifactContentWriter writer,
                                                ArtifactContentRemover remover, IArtifactLedgerRepository artifacts,
                                                IToolInvocationLedgerRepository invocations,
                                                LedgerRecordingService ledger, RuntimeEventMapper events,
                                                RuntimeEventPublisher publisher,
                                                @Value("${artifact.content.write-storage-type:local_artifact_storage}") String storageType,
                                                @Value("${artifact.content.write-prefix:}") String prefix) {
        this.workspace = workspace;
        this.writer = writer;
        this.remover = remover;
        this.artifacts = artifacts;
        this.invocations = invocations;
        this.ledger = ledger;
        this.events = events;
        this.publisher = publisher;
        this.storageType = storageType;
        this.keys = new ArtifactObjectKeyBuilder(prefix);
    }

    /**
     * 核对文件大小与可信工具调用，以 Run、相对路径和内容哈希形成幂等身份。
     * 保存原始字节、记录可见 output Artifact，再发布交付事件；登记失败时尝试回收新写入对象。
     */
    public Map<String, Object> register(AgentContext context, Path path, String toolCallId) throws Exception {
        return register(context, path, toolCallId, "workspace_register", "workspace_register");
    }

    /**
     * 宿主 CLI 新建的工作区文件由当前 host_cli invocation 直接登记，
     * 复用同一内容存储、幂等键、Artifact Ledger 和交付事件流程。
     */
    public Map<String, Object> registerHostCliOutput(AgentContext context, Path path, String toolCallId) throws Exception {
        workspace.writable(context, workspace.relative(context, path));
        return register(context, path, toolCallId, "host_cli", "host_cli");
    }

    /** 发布既有 HTML 时沿用工作区登记合同，并核对当前 canvas_publish 调用身份。 */
    public Map<String, Object> registerCanvasHtml(AgentContext context, Path path, String toolCallId) throws Exception {
        workspace.writable(context, workspace.relative(context, path));
        return register(context, path, toolCallId, "canvas_publish", "canvas_publish");
    }

    /** 浏览器截图沿用工作区产物登记链，并校验调用确实来自 browser/agent_browser。 */
    public Map<String, Object> registerBrowserScreenshot(AgentContext context, Path path,
                                                          String toolCallId, String toolName) throws Exception {
        if (!"browser".equals(toolName) && !"agent_browser".equals(toolName))
            throw new IllegalArgumentException("不支持的浏览器产物来源");
        workspace.writable(context, workspace.relative(context, path));
        return register(context, path, toolCallId, toolName, toolName);
    }

    private Map<String, Object> register(AgentContext context, Path path, String toolCallId,
                                         String invocationToolName, String sourceName) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("待登记文件不存在");
        long size = Files.size(path);
        if (size <= 0 || size > maxBytes) throw new IllegalArgumentException("待登记文件为空或超过配置上限");
        String relative = workspace.relative(context, path);
        String runId = context.getAttribute("runId");
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Run 身份缺失");
        ToolInvocationLedgerEntity invocation = invocations.findByRunId(runId).stream()
                .filter(item -> toolCallId != null && toolCallId.equals(item.getToolCallId())
                        && invocationToolName.equals(item.getToolName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("产物登记缺少可信工具调用"));
        byte[] content = Files.readAllBytes(path);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String identity = runId + ":" + relative + ":" + hash;
        String artifactId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        String idempotencyKey = "workspace:" + artifactId;
        ArtifactLedgerEntity existing = artifacts.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) return result(existing, relative, false);

        String key = keys.build(artifactId);
        String mime = safeMime(path, content);
        ArtifactContentLocator locator = new ArtifactContentLocator(storageType, key, null);
        try {
            writer.write(ArtifactContentWriteRequest.builder().storageType(storageType).storageKey(key)
                    .content(content).mimeType(mime).build());
            Instant now = Instant.now();
            ArtifactLedgerEntity artifact = ArtifactLedgerEntity.builder()
                    .artifactId(artifactId).runId(runId).sessionId(context.getSessionId())
                    .requestId(context.getRequestId()).traceId(context.getTraceId()).toolCallId(toolCallId)
                    .toolInvocationId(invocation.getToolInvocationId())
                    .artifactRole("output").visibility("visible").sourceType(sourceName)
                    .sourceId(relative).sourceName(sourceName)
                    .artifactType("file").artifactName(path.getFileName().toString())
                    .fileName(path.getFileName().toString()).storageType(storageType).storageKey(key)
                    .mimeType(mime).size(size).fileSize(size).fileHash(hash)
                    .contentSummary("工作区生成文件：" + relative)
                    .metadataJson(JSON.toJSONString(metadata(relative, mime, content)))
                    .deleted(0).idempotencyKey(idempotencyKey).createdAt(now).updatedAt(now).build();
            ArtifactLedgerEntity recorded = ledger.recordArtifact(artifact);
            if (recorded == null) throw new IllegalStateException("Artifact 登记失败");
            try { publisher.publishAfterCommit(events.artifactCreated(recorded, context.getAttribute("agentId"))); }
            catch (RuntimeException deliveryFailure) {
                log.warn("产物已登记，但 Live 事件发布失败, artifactId={}", recorded.getArtifactId(), deliveryFailure);
            }
            return result(recorded, relative, true);
        } catch (Exception failure) {
            if (artifacts.findByIdempotencyKey(idempotencyKey).isEmpty()) {
                try { remover.remove(locator); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private Map<String, Object> result(ArtifactLedgerEntity artifact, String path, boolean created) {
        return Map.of("artifactId", artifact.getArtifactId(), "path", path, "name", artifact.getFileName(),
                "bytes", artifact.getFileSize(), "registered", true, "created", created);
    }

    /** 只有内容校验和预览预算均通过才声明 Reader 类型；否则显式标为仅下载。 */
    private Map<String, Object> metadata(String relative, String mime, byte[] content) {
        ArtifactPreviewDescriptor preview = null;
        if (ArtifactPreviewPolicy.isPreviewableRasterImage(mime)
                && ArtifactPreviewPolicy.matchesRasterSignature(mime, content)) {
            preview = ArtifactPreviewDescriptor.imageV1();
        } else if (ArtifactPreviewPolicy.isPdf(mime, relative)
                && ArtifactPreviewPolicy.matchesPdfSignature(content)) {
            preview = ArtifactPreviewDescriptor.pdfV1();
        } else if (ArtifactPreviewPolicy.isDocx(mime, relative)
                && ArtifactPreviewPolicy.matchesOfficeSignature(content)) {
            preview = ArtifactPreviewDescriptor.docxV1();
        } else if (ArtifactPreviewPolicy.isXlsx(mime, relative)
                && ArtifactPreviewPolicy.matchesOfficeSignature(content)) {
            preview = ArtifactPreviewDescriptor.spreadsheetV1();
        } else if (ArtifactPreviewPolicy.isCsv(mime, relative)
                && content.length <= ArtifactPreviewPolicy.CSV_PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isUtf8Text(content)) {
            preview = ArtifactPreviewDescriptor.spreadsheetV1();
        } else if (ArtifactPreviewPolicy.isPlainText(mime)
                && content.length <= ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isUtf8Text(content)) {
            preview = ArtifactPreviewDescriptor.textV1();
        } else if ("text/markdown".equals(mime)
                && content.length <= ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isUtf8Text(content)) {
            preview = ArtifactPreviewDescriptor.markdownV1();
        } else if ("text/html".equals(mime)
                && content.length <= ArtifactPreviewPolicy.HTML_PREVIEW_LIMIT_BYTES
                && ArtifactPreviewPolicy.isUtf8Text(content)) {
            preview = ArtifactPreviewDescriptor.htmlV1();
        }
        // 显式禁用旧的“只按 .md 扩展名提供 Markdown 预览”兼容回退，防止伪造字节被内联。
        return Map.of("workspacePath", relative, "preview", Map.of(
                "kind", preview == null ? "download-only" : preview.getKind(),
                "schemaVersion", ArtifactPreviewDescriptor.V1));
    }

    /** 对可内联的文本和图片核验实际字节；声明与内容不符时使用下载型 MIME。 */
    private String safeMime(Path path, byte[] content) throws Exception {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String imageMime = name.endsWith(".png") ? "image/png"
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                : name.endsWith(".gif") ? "image/gif"
                : name.endsWith(".webp") ? "image/webp"
                : name.endsWith(".bmp") ? "image/bmp" : null;
        if (imageMime != null) return ArtifactPreviewPolicy.matchesRasterSignature(imageMime, content)
                ? imageMime : "application/octet-stream";
        if (name.endsWith(".md")) return ArtifactPreviewPolicy.isUtf8Text(content)
                ? "text/markdown" : "application/octet-stream";
        if (name.endsWith(".txt") || name.endsWith(".csv")) return ArtifactPreviewPolicy.isUtf8Text(content)
                ? (name.endsWith(".csv") ? "text/csv" : "text/plain") : "application/octet-stream";
        if (name.endsWith(".html") || name.endsWith(".htm")) return ArtifactPreviewPolicy.isUtf8Text(content)
                ? "text/html" : "application/octet-stream";
        if (name.endsWith(".pdf")) return ArtifactPreviewPolicy.matchesPdfSignature(content)
                ? "application/pdf" : "application/octet-stream";
        if (name.endsWith(".docx")) return ArtifactPreviewPolicy.matchesOfficeSignature(content)
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/octet-stream";
        if (name.endsWith(".xlsx")) return ArtifactPreviewPolicy.matchesOfficeSignature(content)
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/octet-stream";
        String detected = Files.probeContentType(path);
        return detected == null || detected.isBlank() ? "application/octet-stream" : detected;
    }

}
