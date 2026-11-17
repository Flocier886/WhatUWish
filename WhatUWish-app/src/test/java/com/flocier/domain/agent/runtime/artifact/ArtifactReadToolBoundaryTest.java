package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.ArtifactReadTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolClient;
import com.flocier.domain.agent.runtime.agent.tool.impl.PythonToolResponseEntity;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

/** Agent Artifact 读取仅以稳定 ID 面向模型，locator 只允许出现在 Java 私有请求中。 */
public class ArtifactReadToolBoundaryTest {

    @Test
    public void shouldResolveOnlyCurrentRunVisibleTextArtifact() {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        repository.save(artifact("artifact-1", "run-1", "session-1", "text/markdown", "report.md", 128L));
        ArtifactReadAccessResolver resolver = new ArtifactReadAccessResolver(repository);

        ArtifactLedgerEntity resolved = resolver.resolve(context(), "artifact-1");

        Assert.assertEquals("s3_object_storage", resolved.getStorageType());
        Assert.assertEquals("v1/artifacts/artifact-1/content", resolved.getStorageKey());

        ArtifactLedgerEntity localArtifact = artifact("artifact-local", "run-1", "session-1", "text/plain", "local.txt", 32L);
        localArtifact.setStorageType("local_artifact_storage");
        localArtifact.setStorageKey("v1/artifacts/artifact-local/content");
        repository.save(localArtifact);
        Assert.assertEquals("local_artifact_storage", resolver.resolve(context(), "artifact-local").getStorageType());

        ArtifactLedgerEntity deleted = artifact("artifact-deleted", "run-1", "session-1", "text/plain", "deleted.txt", 16L);
        deleted.setDeleted(1);
        repository.save(deleted);
        assertAccessFailure(resolver, "artifact-deleted", "artifact_read_forbidden");

        repository.save(artifact("artifact-image", "run-1", "session-1", "image/png", "poster.png", 16L));
        assertAccessFailure(resolver, "artifact-image", "artifact_read_unsupported");

        repository.save(artifact("artifact-prior-run", "run-0", "session-1", "text/plain", "prior.txt", 16L));
        Assert.assertEquals("artifact-prior-run", resolver.resolve(context(), "artifact-prior-run").getArtifactId());

        repository.save(artifact("artifact-other-session", "run-2", "session-2", "text/plain", "other.txt", 16L));
        assertAccessFailure(resolver, "artifact-other-session", "artifact_read_forbidden");

        ArtifactLedgerEntity webSource = artifact("web-source", "run-0", "session-1", "text/plain", "web-page.txt", 32L);
        webSource.setArtifactRole("source");
        webSource.setArtifactType("web_page");
        webSource.setSourceType("web_fetch");
        webSource.setSourceName("web_fetch");
        repository.save(webSource);
        Assert.assertEquals("web-source", resolver.resolve(context(), "web-source").getArtifactId());
        webSource.setDeleted(1);
        repository.save(webSource);
        assertAccessFailure(resolver, "web-source", "artifact_read_forbidden");
        webSource.setDeleted(0);
        webSource.setSourceName("another_tool");
        repository.save(webSource);
        assertAccessFailure(resolver, "web-source", "artifact_read_forbidden");
    }

    @Test
    public void shouldInjectLocatorPrivatelyAndProjectOnlyPublicResultFields() {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        repository.save(artifact("artifact-1", "run-1", "session-1", "text/plain", "source.txt", 128L));
        AtomicReference<ToolInvokeRequest> privateRequest = new AtomicReference<>();
        ArtifactReadTool tool = tool(repository, privateRequest);

        Object value = tool.execute(ToolInvokeRequest.builder()
                .toolName("artifact_read")
                .arguments(Map.of("artifactId", "artifact-1", "offset", 10, "limit", 20))
                .build(), context());

        ToolResultPayload result = (ToolResultPayload) value;
        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("artifact-1", privateRequest.get().getArguments().get("artifactId"));
        Assert.assertEquals("s3_object_storage", privateRequest.get().getArguments().get("storageType"));
        Assert.assertEquals("v1/artifacts/artifact-1/content", privateRequest.get().getArguments().get("storageKey"));
        Assert.assertEquals(10, privateRequest.get().getArguments().get("offset"));
        Assert.assertFalse(result.getStructuredOutput().toString().contains("storageKey"));
        Assert.assertFalse(result.getStructuredOutput().toString().contains("storageType"));
        Assert.assertFalse(result.getLlmObservation().contains("storageKey"));
        Assert.assertFalse(result.getLlmObservation().contains("storageType"));
    }

    @Test
    public void shouldRejectLocatorInjectionBeforeCallingPython() {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        AtomicReference<ToolInvokeRequest> privateRequest = new AtomicReference<>();
        ArtifactReadTool tool = tool(repository, privateRequest);

        ToolResultPayload result = (ToolResultPayload) tool.execute(ToolInvokeRequest.builder()
                .toolName("artifact_read")
                .arguments(Map.of(
                        "artifactId", "artifact-1",
                        "storageKey", "v1/artifacts/artifact-1/content"
                ))
                .build(), context());

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("artifact_read_invalid_argument", result.getErrorCode());
        Assert.assertNull(privateRequest.get());
    }

    private ArtifactReadTool tool(InMemoryArtifactLedgerRepository repository,
                                  AtomicReference<ToolInvokeRequest> privateRequest) {
        PythonToolClient client = new PythonToolClient() {
            @Override
            public PythonToolResponseEntity execute(String baseUrl, String endpoint, String toolName,
                                                    ToolInvokeRequest request, AgentContext context) {
                privateRequest.set(request);
                PythonToolResponseEntity response = new PythonToolResponseEntity();
                response.setSuccess(true);
                response.setStatus("success");
                response.setOutput("已读取 artifact：source.txt\n正文片段");
                response.setLlmObservation("已读取 artifact：source.txt\nartifactId: artifact-1\n正文片段");
                response.setStructured(new LinkedHashMap<>(Map.of(
                        "artifactId", "artifact-1",
                        "fileName", "source.txt",
                        "contentLength", 128,
                        "offset", 10,
                        "hasMore", false,
                        "storageType", "s3_object_storage",
                        "storageKey", "v1/artifacts/artifact-1/content"
                )));
                return response;
            }
        };
        return new ArtifactReadTool(
                "artifact_read", "read", Map.of(), "http://unused", "/unused", client,
                new ArtifactReadAccessResolver(repository)
        );
    }

    private void assertAccessFailure(ArtifactReadAccessResolver resolver, String artifactId, String errorCode) {
        try {
            resolver.resolve(context(), artifactId);
            Assert.fail("当前 Artifact 应被拒绝读取");
        } catch (ArtifactReadAccessException exception) {
            Assert.assertEquals(errorCode, exception.getErrorCode());
        }
    }

    private ArtifactLedgerEntity artifact(String artifactId, String runId, String sessionId,
                                          String mimeType, String fileName, long fileSize) {
        return ArtifactLedgerEntity.builder()
                .artifactId(artifactId)
                .runId(runId)
                .sessionId(sessionId)
                .artifactRole("output")
                .visibility("visible")
                .artifactType("file")
                .artifactName(fileName)
                .fileName(fileName)
                .mimeType(mimeType)
                .fileSize(fileSize)
                .size(fileSize)
                .storageType("s3_object_storage")
                .storageKey("v1/artifacts/" + artifactId + "/content")
                .storageRef("private/" + artifactId)
                .deleted(0)
                .build();
    }

    private AgentContext context() {
        AgentContext context = AgentContext.builder().sessionId("session-1").build();
        context.setAttribute("runId", "run-1");
        return context;
    }
}
