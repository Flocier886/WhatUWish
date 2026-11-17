package com.flocier.test.domain.cli;

import com.flocier.domain.agent.adapter.port.cli.CliExecutionPort;
import com.flocier.domain.agent.adapter.port.cli.CliInvocation;
import com.flocier.domain.agent.adapter.port.cli.CliResult;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.HostCliTool;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.file.WorkspaceArtifactRegistrationService;
import com.flocier.types.agent.config.HostCliProperties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class HostCliToolTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void rejectsUnknownToolsAndSocialWriteCommands() throws Exception {
        Fixture fixture = fixture("http://proxy.test:8080");
        try {
            ToolResultPayload unknown = fixture.run("rm", List.of("-rf", "/"));
            ToolResultPayload twitter = fixture.run("twitter", List.of("post", "hello"));
            ToolResultPayload reddit = fixture.run("rdt", List.of("upvote", "abc"));
            assertEquals("host_cli_denied", unknown.getErrorCode());
            assertEquals("host_cli_denied", twitter.getErrorCode());
            assertEquals("host_cli_denied", reddit.getErrorCode());
            verify(fixture.cli, never()).exec(any());
        } finally {
            fixture.workspace.finish(fixture.context);
        }
    }

    @Test
    public void preservesCliFactsProxyAndWorkspaceCwd() throws Exception {
        Fixture fixture = fixture("http://proxy.test:8080");
        try {
            when(fixture.cli.exec(any())).thenReturn(CliResult.builder()
                    .ok(true).tool("twitter").exitCode(0).stdout("post result")
                    .stderr("").durationMs(3).artifacts(List.of()).build());
            ToolResultPayload result = fixture.run("twitter", List.of("search", "open source"));
            assertTrue(result.isSuccess());
            assertTrue(result.getLlmObservation().contains("post result"));
            assertTrue(result.getLlmObservation().contains("exit_code"));
            ArgumentCaptor<CliInvocation> captured = ArgumentCaptor.forClass(CliInvocation.class);
            verify(fixture.cli).exec(captured.capture());
            assertEquals(List.of("search", "open source"), captured.getValue().getArgs());
            assertEquals(fixture.workspace.root(fixture.context).toString(), captured.getValue().getCwd());
            assertEquals("http://proxy.test:8080", captured.getValue().getEnv().get("HTTPS_PROXY"));
            assertEquals(120_000L, captured.getValue().getTimeoutMs());
        } finally {
            fixture.workspace.finish(fixture.context);
        }
    }

    @Test
    public void reportsCliFailureWithoutDiscardingStderr() throws Exception {
        Fixture fixture = fixture("");
        try {
            when(fixture.cli.exec(any())).thenReturn(CliResult.builder()
                    .ok(false).tool("rdt").exitCode(2).stdout("")
                    .stderr("login required").durationMs(7).artifacts(List.of()).build());
            ToolResultPayload result = fixture.run("rdt", List.of("search", "news"));
            assertFalse(result.isSuccess());
            assertTrue(result.getLlmObservation().contains("login required"));
            ArgumentCaptor<CliInvocation> captured = ArgumentCaptor.forClass(CliInvocation.class);
            verify(fixture.cli).exec(captured.capture());
            assertTrue(captured.getValue().getUnsetEnv().contains("HTTPS_PROXY"));
        } finally {
            fixture.workspace.finish(fixture.context);
        }
    }

    @Test
    public void registersNewOutputFilesWithRelativePath() throws Exception {
        Fixture fixture = fixture("");
        try {
            when(fixture.cli.exec(any())).thenAnswer(call -> {
                Path output = fixture.workspace.writable(fixture.context, "outputs/post.txt");
                Files.writeString(output, "saved post");
                return CliResult.builder().ok(true).tool("twitter").exitCode(0)
                        .stdout("saved").stderr("").durationMs(2).artifacts(List.of()).build();
            });
            when(fixture.registration.registerHostCliOutput(eq(fixture.context), any(Path.class), eq("call")))
                    .thenReturn(Map.of("path", "outputs/post.txt", "artifactId", "artifact-1",
                            "name", "post.txt", "bytes", 10L));
            ToolResultPayload result = fixture.run("twitter", List.of("search", "news"));
            assertTrue(result.isSuccess());
            assertTrue(result.getLlmObservation().contains("artifact-1"));
            assertTrue(result.getLlmObservation().contains("outputs/post.txt"));
            assertEquals(List.of("artifact-1"), result.getArtifactRefs());
            assertFalse(result.getLlmObservation().contains(fixture.workspace.root(fixture.context).toString()));
            verify(fixture.registration).registerHostCliOutput(eq(fixture.context), any(Path.class), eq("call"));
        } finally {
            fixture.workspace.finish(fixture.context);
        }
    }

    @Test
    public void reportsUnregisteredFileWithoutLosingCliOutput() throws Exception {
        Fixture fixture = fixture("");
        try {
            when(fixture.cli.exec(any())).thenAnswer(call -> {
                Files.writeString(fixture.workspace.writable(fixture.context, "outputs/unregistered.txt"), "payload");
                return CliResult.builder().ok(true).tool("rdt").exitCode(0)
                        .stdout("posts found").stderr("").durationMs(2).artifacts(List.of()).build();
            });
            when(fixture.registration.registerHostCliOutput(eq(fixture.context), any(Path.class), eq("call")))
                    .thenThrow(new IllegalArgumentException("registration failed"));
            ToolResultPayload result = fixture.run("rdt", List.of("search", "news"));
            assertTrue(result.isSuccess());
            assertTrue(result.getLlmObservation().contains("posts found"));
            assertTrue(result.getLlmObservation().contains("outputs/unregistered.txt"));
            assertTrue(result.getLlmObservation().contains("Artifact 登记失败"));
            assertFalse(result.getLlmObservation().contains("artifactId"));
            assertTrue(result.getArtifactRefs().isEmpty());
        } finally {
            fixture.workspace.finish(fixture.context);
        }
    }

    private Fixture fixture(String proxy) throws Exception {
        RunWorkspaceService workspace = new RunWorkspaceService(mock(com.flocier.domain.agent.runtime.artifact.content.ArtifactContentStore.class),
                mock(com.flocier.domain.agent.runtime.file.ConversationFileRepository.class),
                temporary.newFolder("runs").getPath());
        AgentContext context = AgentContext.builder().sessionId("session").requestId("request").build();
        context.setAttribute("runId", "run");
        workspace.prepare(context, List.of());
        CliExecutionPort cli = mock(CliExecutionPort.class);
        when(cli.isResolvable(anyString())).thenReturn(true);
        HostCliProperties properties = new HostCliProperties();
        properties.setAllow(List.of("twitter", "rdt"));
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "webFetchProxy", proxy);
        WorkspaceArtifactRegistrationService registration = mock(WorkspaceArtifactRegistrationService.class);
        HostCliTool tool = new HostCliTool(List.of("twitter", "rdt"), cli, properties, config, workspace, registration);
        return new Fixture(tool, cli, workspace, registration, context);
    }

    private record Fixture(HostCliTool tool, CliExecutionPort cli, RunWorkspaceService workspace,
                           WorkspaceArtifactRegistrationService registration, AgentContext context) {
        private ToolResultPayload run(String cliName, List<String> args) {
            return (ToolResultPayload) tool.execute(ToolInvokeRequest.builder()
                    .toolName("host_cli").toolCallId("call")
                    .arguments(Map.of("tool", cliName, "args", args)).build(), context);
        }
    }
}
