package com.flocier.test.domain.tool;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollectionFactory;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("dev")
public class PythonToolRuntimeLiveTest {

    @Autowired
    private ToolCollectionFactory toolCollectionFactory;

    @Autowired
    private ToolRunner toolRunner;

    @Test
    public void shouldExecuteDirectoryListThroughPythonFastApi() {
        AgentContext context = buildContext("python-live-directory-list");
        ToolExecutionResult result = execute(
                context,
                "directory_list",
                Map.of(
                        "path", "WhatUWish-tools-python/app/tools",
                        "maxDepth", 1,
                        "includeHidden", false
                )
        );

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("directory_list", result.getToolName());
        Assert.assertTrue(result.getOutputText().contains("tools"));
    }

    @Test
    public void shouldExecuteFileSearchThroughPythonFastApi() {
        AgentContext context = buildContext("python-live-file-search");
        ToolExecutionResult result = execute(
                context,
                "file_search",
                Map.of(
                        "query", "artifact_save",
                        "path", "WhatUWish-tools-python/app/tools",
                        "limit", 5
                )
        );

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("file_search", result.getToolName());
        Assert.assertNotNull(result.getOutputText());
        Assert.assertFalse(result.getOutputText().isBlank());
    }

    @Test
    public void shouldExecuteFileReadThroughPythonFastApi() {
        AgentContext context = buildContext("python-live-file-read");
        ToolExecutionResult result = execute(
                context,
                "file_read",
                Map.of("path", "WhatUWish-tools-python/README.md")
        );

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("python", result.getSourceType());
        Assert.assertEquals("file_read", result.getToolName());
        Assert.assertNotNull(result.getOutputText());
        Assert.assertFalse(result.getOutputText().isBlank());
        Assert.assertTrue(result.getOutputText().contains("FastAPI") || result.getOutputText().contains("Python"));
    }

    @Test
    public void shouldExecuteArtifactSaveAndReadThroughPythonFastApi() {
        AgentContext context = buildContext("python-live-artifact-roundtrip");
        String marker = "java-live-artifact-marker-" + UUID.randomUUID();
        ToolExecutionResult saveResult = execute(
                context,
                "artifact_save",
                Map.of(
                        "name", "java-live-test-note-" + UUID.randomUUID() + ".txt",
                        "content", marker,
                        "artifactType", "text"
                )
        );

        Assert.assertTrue(saveResult.isSuccess());
        Assert.assertEquals("artifact_save", saveResult.getToolName());
        Assert.assertFalse(saveResult.getArtifactDescriptors().isEmpty());
        Map<String, Object> artifact = saveResult.getArtifactDescriptors().get(0);

        String storageKey = String.valueOf(artifact.get("storageKey"));
        String artifactId = String.valueOf(artifact.get("artifactId"));
        Assert.assertNotNull(storageKey);
        Assert.assertFalse(storageKey.isBlank());
        Assert.assertNotNull(artifactId);
        Assert.assertFalse(artifactId.isBlank());

        ToolExecutionResult readByStorageKey = execute(
                context,
                "artifact_read",
                Map.of("storageKey", storageKey)
        );

        Assert.assertTrue(readByStorageKey.isSuccess());
        Assert.assertEquals("artifact_read", readByStorageKey.getToolName());
        Assert.assertTrue(readByStorageKey.getOutputText().contains(marker));

        ToolExecutionResult readByArtifactId = execute(
                context,
                "artifact_read",
                Map.of("artifactId", artifactId)
        );

        Assert.assertTrue(readByArtifactId.isSuccess());
        Assert.assertEquals("artifact_read", readByArtifactId.getToolName());
        Assert.assertTrue(readByArtifactId.getOutputText().contains(marker));
        Assert.assertEquals(1, context.requireArtifactRuntimeRegistry().listBindings().size());
    }

    @Test
    public void shouldExecuteJsonExtractThroughPythonFastApi() {
        AgentContext context = buildContext("python-live-json-extract");
        ToolExecutionResult result = execute(
                context,
                "json_extract",
                Map.of(
                        "content", "{\"name\":\"ledger\",\"module\":\"tool\",\"status\":\"ready\"}",
                        "fields", List.of("name", "status")
                )
        );

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("json_extract", result.getToolName());
        Assert.assertNotNull(result.getOutputText());
        Assert.assertTrue(result.getOutputText().contains("ledger"));
        Assert.assertTrue(result.getOutputText().contains("ready"));
    }

    private ToolExecutionResult execute(AgentContext context, String toolName, Map<String, Object> arguments) {
        ToolCollection toolCollection = toolCollectionFactory.create(context);
        return toolRunner.run(
                toolCollection,
                ToolInvokeRequest.builder()
                        .toolName(toolName)
                        .toolCallId("call-" + toolName + "-" + UUID.randomUUID())
                        .requestId(context.getRequestId())
                        .sessionId(context.getSessionId())
                        .step(1)
                        .arguments(arguments)
                        .build(),
                context
        );
    }

    private AgentContext buildContext(String prefix) {
        String sessionId = prefix + "-" + UUID.randomUUID();
        AgentContext context = AgentContext.builder()
                .requestId(prefix + "-request")
                .sessionId(sessionId)
                .query(prefix)
                .task(prefix)
                .maxStep(3)
                .build();
        context.setAttribute("runId", prefix + "-run-" + UUID.randomUUID());
        context.setAttribute("agentId", "react-live");
        return context;
    }
}
