package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.ArtifactReplayResolver;
import com.flocier.domain.agent.runtime.artifact.ArtifactReplayResult;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeBinding;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeCollector;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeReadReference;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeReferenceResolver;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeDescriptor;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeFormatter;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeRegistry;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeSource;
import com.flocier.domain.agent.runtime.artifact.ArtifactSelectionParser;
import com.flocier.domain.agent.runtime.artifact.ArtifactSelectionResult;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.ledger.service.LedgerQueryService;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ArtifactRuntimeClosureTest {

    @Test
    public void shouldDeduplicateWithinSourceButKeepDifferentToolCalls() {
        ArtifactRuntimeRegistry registry = new ArtifactRuntimeRegistry();
        ArtifactRuntimeDescriptor descriptor = descriptor("artifact-1", "report.md", "visible", "output");
        ArtifactRuntimeSource first = source("call-1");
        ArtifactRuntimeSource second = source("call-2");

        registry.register(first, descriptor);
        registry.register(first, descriptor);
        registry.register(second, descriptor);

        Assert.assertEquals(2, registry.listBindings().size());
        Assert.assertEquals(1, registry.findByToolCallId("call-1").size());
        Assert.assertEquals(1, registry.findByToolCallId("call-2").size());
        Assert.assertEquals("call-1::artifact-1", registry.findByToolCallId("call-1").get(0).artifactKey());
    }

    @Test
    public void shouldOnlyExposeVisibleOutputAndParseKnownKeys() {
        ArtifactRuntimeRegistry registry = new ArtifactRuntimeRegistry();
        ArtifactRuntimeBinding visible = registry.register(source("call-visible"), descriptor("artifact-visible", "report.md", "visible", "output"));
        registry.register(source("call-internal"), descriptor("artifact-internal", "secret.txt", "internal", "output"));
        registry.register(source("call-preview"), descriptor("artifact-preview", "preview.txt", "visible", "intermediate"));

        ArtifactRuntimeFormatter formatter = new ArtifactRuntimeFormatter();
        String observation = formatter.appendToolArtifactSummary("tool completed", registry.findByToolCallId("call-visible"));
        Assert.assertTrue(observation.contains(visible.artifactKey()));
        Assert.assertFalse(observation.contains("secret.txt"));
        Assert.assertFalse(observation.contains("test artifact"));

        ArtifactSelectionResult selection = new ArtifactSelectionParser().parse(
                "done $$$ " + visible.artifactKey() + " unknown::key call-internal::artifact-internal", registry);
        Assert.assertTrue(selection.isExplicitSelection());
        Assert.assertEquals("done", selection.getSummaryText());
        Assert.assertEquals(List.of(visible.artifactKey()), selection.getArtifactRefs());
    }

    @Test
    public void shouldReplayOnlyRealVisibleOutputArtifacts() {
        ArtifactLedgerEntity output = ledger("artifact-output", "call-output", "visible", "output", 0, "file", "local_artifact_storage");
        ArtifactLedgerEntity internal = ledger("artifact-internal", "call-internal", "internal", "output", 0, "file", "local_artifact_storage");
        ArtifactLedgerEntity deleted = ledger("artifact-deleted", "call-deleted", "visible", "output", 1, "file", "local_artifact_storage");
        ArtifactLedgerEntity pseudo = ledger("artifact-pseudo", "call-pseudo", "visible", "output", 0, "tool_result", "inline_summary");
        ArtifactReplayResolver resolver = new ArtifactReplayResolver();

        ArtifactReplayResult explicit = resolver.resolve("summary $$$ call-output::artifact-output", List.of(output, internal, deleted, pseudo));
        Assert.assertTrue(explicit.isExplicitSelection());
        Assert.assertEquals("summary", explicit.getSummaryText());
        Assert.assertEquals(List.of("call-output::artifact-output"), explicit.getArtifactRefs());

        ArtifactReplayResult fallback = resolver.resolve("summary without selection", List.of(output, internal, deleted, pseudo));
        Assert.assertFalse(fallback.isExplicitSelection());
        Assert.assertEquals(List.of("call-output::artifact-output"), fallback.getArtifactRefs());
    }

    @Test
    public void shouldReturnSelectedArtifactsSeparatelyFromSummaryText() {
        ArtifactRuntimeRegistry registry = new ArtifactRuntimeRegistry();
        ArtifactRuntimeBinding binding = registry.register(source("call-result"),
                descriptor("artifact-result", "delivery.md", "visible", "output"));
        ArtifactSelectionResult selection = new ArtifactSelectionParser().parse(
                "delivery summary $$$ " + binding.artifactKey(), registry);

        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createSummaryResult(
                selection.getSummaryText(), "session-1", selection);

        Assert.assertEquals("delivery summary", result.getContent());
        Assert.assertEquals(List.of(binding.artifactKey()), result.getArtifactRefs());
        Assert.assertEquals(1, result.getFileList().size());
        Assert.assertEquals("delivery.md", result.getFileList().get(0).getName());
        Assert.assertEquals("test artifact", result.getFileList().get(0).getSummary());
        Assert.assertEquals("run-1", result.getFileList().get(0).getRunId());
        Assert.assertTrue(result.getFileList().get(0).isPreviewSupported());
        Assert.assertTrue(result.getFileList().get(0).isDownloadSupported());
    }

    @Test
    public void shouldResolveCurrentArtifactOnlyThroughRegisteredReference() {
        ArtifactRuntimeRegistry registry = new ArtifactRuntimeRegistry();
        ArtifactRuntimeBinding binding = registry.register(source("call-read"),
                descriptor("artifact-read", "readable.md", "visible", "output"));
        AgentContext context = AgentContext.builder().artifactRuntimeRegistry(registry).build();
        ArtifactRuntimeReferenceResolver resolver = new ArtifactRuntimeReferenceResolver(null);

        ArtifactRuntimeReadReference read = resolver.resolveCurrent(context, binding.artifactKey());
        ArtifactRuntimeReadReference rejected = resolver.resolveCurrent(context, "C:/unregistered/file.txt");

        Assert.assertNotNull(read);
        Assert.assertEquals(binding.artifactKey(), read.getArtifactKey());
        Assert.assertNull(rejected);
    }

    @Test
    public void shouldResolveOnlyActiveRealHistoricalArtifactReferences() {
        ArtifactLedgerEntity available = ledger(
                "artifact-available",
                "call-available",
                "visible",
                "output",
                0,
                "file",
                "local_artifact_storage"
        );
        ArtifactLedgerEntity deleted = ledger(
                "artifact-deleted",
                "call-deleted",
                "visible",
                "output",
                1,
                "file",
                "local_artifact_storage"
        );
        ArtifactLedgerEntity pseudo = ledger(
                "artifact-pseudo",
                "call-pseudo",
                "visible",
                "output",
                0,
                "tool_result",
                "inline_summary"
        );
        LedgerQueryService queryService = runViewQueryService(List.of(available, deleted, pseudo));
        ArtifactRuntimeReferenceResolver resolver = new ArtifactRuntimeReferenceResolver(queryService);

        ArtifactRuntimeReadReference actual = resolver.resolveHistorical(
                "run-1",
                "call-available::artifact-available"
        );

        Assert.assertNotNull(actual);
        Assert.assertEquals("artifact-available", actual.getArtifactId());
        Assert.assertNull(resolver.resolveHistorical("run-1", "call-deleted::artifact-deleted"));
        Assert.assertNull(resolver.resolveHistorical("run-1", "call-pseudo::artifact-pseudo"));
    }

    @Test
    public void shouldOnlyCollectCompleteExplicitArtifactDescriptors() {
        ArtifactRuntimeCollector collector = new ArtifactRuntimeCollector();
        Map<String, Object> valid = completeDescriptor("artifact-explicit", "report.md");
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("artifact_save")
                .toolCallId("call-explicit")
                .artifactDescriptors(List.of(valid))
                .structuredOutput(Map.of(
                        "content", "ordinary tool output",
                        "artifactRefs", List.of(valid)
                ))
                .build();

        List<ArtifactRuntimeDescriptor> descriptors = collector.collect(
                request("artifact_save", "call-explicit"),
                result,
                context()
        );

        Assert.assertEquals(1, descriptors.size());
        Assert.assertEquals("artifact-explicit", descriptors.get(0).getArtifactId());
    }

    @Test
    public void shouldRejectIncompleteAndNonMapArtifactReferences() {
        ArtifactRuntimeCollector collector = new ArtifactRuntimeCollector();
        Map<String, Object> incomplete = Map.of(
                "artifactId", "artifact-incomplete",
                "fileName", "missing-metadata.md",
                "storageKey", "artifacts/missing-metadata.md"
        );
        ToolExecutionResult result = ToolExecutionResult.builder()
                .toolName("artifact_read")
                .toolCallId("call-read")
                .llmObservation("read result")
                .artifactDescriptors(List.of(incomplete))
                .structuredOutput(Map.of(
                        "content", "read body is not an artifact",
                        "artifactRefs", List.of("artifacts/existing.md")
                ))
                .build();

        Assert.assertTrue(collector.collect(request("artifact_read", "call-read"), result, context()).isEmpty());
    }

    private LedgerQueryService runViewQueryService(List<ArtifactLedgerEntity> artifacts) {
        return new LedgerQueryService(null, null, null, null, null) {
            @Override
            public Optional<LedgerRunView> queryRunView(String runId) {
                return Optional.of(LedgerRunView.builder().artifacts(artifacts).build());
            }
        };
    }

    private ToolInvokeRequest request(String toolName, String toolCallId) {
        return ToolInvokeRequest.builder()
                .toolName(toolName)
                .toolCallId(toolCallId)
                .requestId("request-1")
                .sessionId("session-1")
                .step(1)
                .arguments(Map.of())
                .build();
    }

    private AgentContext context() {
        AgentContext context = AgentContext.builder()
                .requestId("request-1")
                .sessionId("session-1")
                .traceId("trace-1")
                .currentStep(1)
                .build();
        context.setAttribute("runId", "run-1");
        return context;
    }

    private Map<String, Object> completeDescriptor(String artifactId, String fileName) {
        return Map.ofEntries(
                Map.entry("artifactId", artifactId),
                Map.entry("name", fileName),
                Map.entry("artifactType", "markdown"),
                Map.entry("artifactRole", "output"),
                Map.entry("visibility", "visible"),
                Map.entry("sourceType", "artifact_save"),
                Map.entry("sourceName", "artifact_save"),
                Map.entry("fileName", fileName),
                Map.entry("storageType", "local_artifact_storage"),
                Map.entry("storageKey", "artifacts/" + artifactId + ".md"),
                Map.entry("storageRef", "artifacts/" + artifactId + ".md"),
                Map.entry("contentSummary", "test artifact summary"),
                Map.entry("mimeType", "text/markdown"),
                Map.entry("fileSize", 32)
        );
    }

    private ArtifactRuntimeSource source(String toolCallId) {
        return ArtifactRuntimeSource.builder().runId("run-1").requestId("request-1").sessionId("session-1")
                .traceId("trace-1").step(1).toolCallId(toolCallId).toolName("file_write").build();
    }

    private ArtifactRuntimeDescriptor descriptor(String artifactId, String fileName, String visibility, String role) {
        return ArtifactRuntimeDescriptor.builder().artifactId(artifactId).name(fileName).fileName(fileName)
                .artifactType("file").artifactRole(role).visibility(visibility).sourceType("file_write")
                .sourceName("file_write").storageType("local_artifact_storage").storageKey("session/run/" + artifactId + "-" + fileName)
                .storageRef("session/run/" + artifactId + "-" + fileName).contentSummary("test artifact").fileSize(12L).build();
    }

    private ArtifactLedgerEntity ledger(String artifactId, String toolCallId, String visibility, String role,
                                        Integer deleted, String type, String storageType) {
        return ArtifactLedgerEntity.builder().artifactId(artifactId).toolCallId(toolCallId).visibility(visibility)
                .artifactRole(role).deleted(deleted).artifactType(type).storageType(storageType)
                .fileName(artifactId + ".txt").storageKey("session/run/" + artifactId).build();
    }
}
