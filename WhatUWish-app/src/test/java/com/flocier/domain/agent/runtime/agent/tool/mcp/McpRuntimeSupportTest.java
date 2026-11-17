package com.flocier.domain.agent.runtime.agent.tool.mcp;

import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.vo.AiClientToolMcpVO;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeCollector;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriteRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentWriter;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactStoredContent;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class McpRuntimeSupportTest {

    @Test
    public void shouldDiscoverNamespacedToolAndCallOriginalRemoteName() {
        IAgentRepository repository = Mockito.mock(IAgentRepository.class);
        McpClientRuntimeFactory factory = Mockito.mock(McpClientRuntimeFactory.class);
        McpSyncClient client = Mockito.mock(McpSyncClient.class);
        AiClientToolMcpVO config = config("server.one", "stdio");
        Mockito.when(repository.queryEnabledAiClientToolMcpVOList()).thenReturn(List.of(config));
        Mockito.when(factory.create(config)).thenReturn(new McpClientRuntime("server.one", "stdio", client));
        Mockito.when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                McpSchema.Tool.builder()
                        .name("search-web")
                        .description("search remote web")
                        .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                                "query", Map.of("type", "string")
                        ), List.of("query"), false, Map.of(), Map.of()))
                        .build()
        ), null));
        Mockito.when(client.callTool(Mockito.any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false));

        McpRuntimeRegistry registry = new McpRuntimeRegistry(repository, factory);
        registry.reload();

        assertEquals(1, registry.listTools().size());
        McpToolDefinition definition = registry.listTools().get(0);
        assertEquals("mcp__server_one__search-web", definition.getExposedToolName());
        assertEquals("string", ((Map<?, ?>) definition.getInputSchema().get("properties")).get("query") instanceof Map<?, ?>
                ? ((Map<?, ?>) ((Map<?, ?>) definition.getInputSchema().get("properties")).get("query")).get("type") : null);

        registry.callTool(definition, Map.of("query", "MCP"));
        ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        Mockito.verify(client).callTool(captor.capture());
        assertEquals("search-web", captor.getValue().name());
        assertEquals("MCP", captor.getValue().arguments().get("query"));

        registry.close();
        Mockito.verify(client).close();
    }

    @Test
    public void shouldKeepSameRemoteToolNamesIsolatedByMcpId() {
        assertEquals("mcp__server-a__search", McpRuntimeRegistry.exposedName("server-a", "search"));
        assertEquals("mcp__server-b__search", McpRuntimeRegistry.exposedName("server-b", "search"));
    }

    @Test
    public void shouldIsolateBrokenServerAndKeepHealthyDefinitions() {
        IAgentRepository repository = Mockito.mock(IAgentRepository.class);
        McpClientRuntimeFactory factory = Mockito.mock(McpClientRuntimeFactory.class);
        AiClientToolMcpVO broken = config("broken", "sse");
        AiClientToolMcpVO healthy = config("healthy", "stdio");
        McpSyncClient healthyClient = Mockito.mock(McpSyncClient.class);
        Mockito.when(repository.queryEnabledAiClientToolMcpVOList()).thenReturn(List.of(broken, healthy));
        Mockito.when(factory.create(broken)).thenThrow(new IllegalStateException("offline"));
        Mockito.when(factory.create(healthy)).thenReturn(new McpClientRuntime("healthy", "stdio", healthyClient));
        Mockito.when(healthyClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                McpSchema.Tool.builder().name("echo").description("echo")
                        .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), true, Map.of(), Map.of()))
                        .build()
        ), null));

        McpRuntimeRegistry registry = new McpRuntimeRegistry(repository, factory);
        registry.reload();

        assertEquals(List.of("mcp__healthy__echo"),
                registry.listTools().stream().map(McpToolDefinition::getExposedToolName).toList());
        registry.close();
    }

    @Test
    public void shouldMapTextStructuredResourceLinkAndRemoteErrorWithoutLeakingBinaryFields() {
        McpToolResultMapper mapper = mapper(request -> new ArtifactStoredContent(request.getContent().length));
        McpToolDefinition definition = definition();
        McpSchema.ResourceLink link = McpSchema.ResourceLink.builder()
                .name("manual").uri("https://example.com/manual").mimeType("text/html").size(120L).build();
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addContent(new McpSchema.TextContent("search completed"))
                .addContent(link)
                .structuredContent(Map.of(
                        "answer", "result",
                        "data", "QUJDREVGRw==",
                        "nested", Map.of("storageKey", "private-key", "count", 2)
                ))
                .isError(false)
                .build();

        ToolResultPayload payload = mapper.map(definition, result);

        assertTrue(payload.isSuccess());
        assertEquals("success", payload.getStatus());
        assertTrue(payload.getLlmObservation().contains("search completed"));
        assertTrue(payload.getLlmObservation().contains("https://example.com/manual"));
        assertFalse(payload.getLlmObservation().contains("QUJDREVGRw=="));
        assertFalse(JSONText(payload.getStructuredOutput()).contains("private-key"));
        assertTrue(payload.getArtifactDescriptors().isEmpty());

        ToolResultPayload failed = mapper.map(definition,
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("permission denied")), true));
        assertFalse(failed.isSuccess());
        assertEquals("failed", failed.getStatus());
        assertEquals("mcp_tool_error", failed.getErrorCode());
        assertTrue(failed.getLlmObservation().contains("permission denied"));
    }

    @Test
    public void shouldMaterializeImageAsCompleteArtifactAndKeepBase64OutOfResult() {
        byte[] image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        ArtifactContentWriter writer = Mockito.mock(ArtifactContentWriter.class);
        Mockito.when(writer.write(Mockito.any())).thenAnswer(invocation -> {
            ArtifactContentWriteRequest request = invocation.getArgument(0);
            return new ArtifactStoredContent(request.getContent().length);
        });
        McpToolResultMapper mapper = mapper(writer);
        String encoded = Base64.getEncoder().encodeToString(image);

        ToolResultPayload payload = mapper.map(definition(), new McpSchema.CallToolResult(List.of(
                new McpSchema.ImageContent(null, encoded, "image/png")
        ), false));

        assertTrue(payload.isSuccess());
        assertEquals(1, payload.getArtifactDescriptors().size());
        assertEquals(1, payload.getArtifactRefs().size());
        assertFalse(payload.getLlmObservation().contains(encoded));
        assertFalse(JSONText(payload.getStructuredOutput()).contains(encoded));
        Map<String, Object> descriptor = payload.getArtifactDescriptors().get(0);
        assertEquals("visible", descriptor.get("visibility"));
        assertEquals("output", descriptor.get("artifactRole"));
        assertEquals("mcp", descriptor.get("sourceType"));
        assertNotNull(descriptor.get("storageKey"));
        assertNotNull(descriptor.get("fileHash"));

        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .toolName(definition().getExposedToolName())
                .success(true)
                .status("success")
                .artifactDescriptors(payload.getArtifactDescriptors())
                .build();
        assertEquals(1, new ArtifactRuntimeCollector().collect(
                ToolInvokeRequest.builder().toolName(definition().getExposedToolName()).toolCallId("call-1").build(),
                executionResult,
                AgentContext.builder().build()
        ).size());
    }

    @Test
    public void shouldReturnPartialSuccessWhenTextSurvivesInvalidBinaryContent() {
        McpToolResultMapper mapper = mapper(request -> new ArtifactStoredContent(request.getContent().length));
        ToolResultPayload payload = mapper.map(definition(), new McpSchema.CallToolResult(List.of(
                new McpSchema.TextContent("usable text"),
                new McpSchema.ImageContent(null, "not-base64", "image/png")
        ), false));

        assertTrue(payload.isSuccess());
        assertEquals("partial_success", payload.getStatus());
        assertTrue(payload.getLlmObservation().contains("usable text"));
        assertTrue(payload.getLlmObservation().contains("部分二进制内容未能交付"));
        assertFalse(payload.getLlmObservation().contains("not-base64"));
    }

    @Test
    public void shouldMaterializeEmbeddedBlobWithoutExposingBlobPayload() {
        byte[] bytes = "%PDF-1.7\nbinary".getBytes(StandardCharsets.UTF_8);
        McpToolResultMapper mapper = mapper(request -> new ArtifactStoredContent(request.getContent().length));
        String encoded = Base64.getEncoder().encodeToString(bytes);
        McpSchema.EmbeddedResource embedded = new McpSchema.EmbeddedResource(null,
                new McpSchema.BlobResourceContents("mcp://files/report.pdf", "application/pdf", encoded));

        ToolResultPayload payload = mapper.map(definition(),
                new McpSchema.CallToolResult(List.of(embedded), false));

        assertTrue(payload.isSuccess());
        assertEquals(1, payload.getArtifactDescriptors().size());
        assertEquals("file", payload.getArtifactDescriptors().get(0).get("artifactType"));
        assertEquals("report.pdf", payload.getArtifactDescriptors().get(0).get("fileName"));
        assertFalse(payload.getLlmObservation().contains(encoded));
        assertFalse(JSONText(payload.getStructuredOutput()).contains(encoded));
    }

    private McpToolResultMapper mapper(ArtifactContentWriter writer) {
        McpToolResultMapper mapper = new McpToolResultMapper(writer);
        ReflectionTestUtils.setField(mapper, "writeStorageType", "local_artifact_storage");
        ReflectionTestUtils.setField(mapper, "writePrefix", "");
        return mapper;
    }

    private AiClientToolMcpVO config(String mcpId, String transportType) {
        return AiClientToolMcpVO.builder().mcpId(mcpId).mcpName(mcpId)
                .transportType(transportType).requestTimeout(1).build();
    }

    private McpToolDefinition definition() {
        return McpToolDefinition.builder()
                .mcpId("server-1")
                .remoteToolName("search")
                .exposedToolName("mcp__server-1__search")
                .description("search")
                .transportType("stdio")
                .inputSchema(Map.of("type", "object"))
                .build();
    }

    private String JSONText(Object value) {
        return com.alibaba.fastjson.JSON.toJSONString(value);
    }
}
