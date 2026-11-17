package com.flocier.test.domain.cli;

import com.flocier.domain.agent.adapter.port.AgentBrowserSession;
import com.flocier.domain.agent.adapter.port.AgentBrowserSessionPort;
import com.flocier.domain.agent.adapter.port.BrowserRelaySocketPort;
import com.flocier.domain.agent.adapter.port.cli.CliExecutionPort;
import com.flocier.domain.agent.adapter.port.cli.CliInvocation;
import com.flocier.domain.agent.adapter.port.cli.CliResult;
import com.flocier.domain.agent.browser.LocalBrowserOwner;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.OpenCliBrowserTool;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.file.WorkspaceArtifactRegistrationService;
import com.flocier.types.agent.config.BrowserRelayProperties;
import com.flocier.types.agent.config.OpenCliProperties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OpenCliBrowserToolTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void bothBrowserEntrypointsDescribeScalableCommandDiscovery() throws Exception {
        for (boolean remote : List.of(false, true)) {
            Fixture f = new Fixture(remote);
            String params = f.tool.params().toString();
            assertTrue(params.contains("[--help] 看站点索引"));
            assertTrue(params.contains("[<site>, <command>, --help, -f, yaml]"));
            assertTrue(params.contains("list 输出全部站点详细说明，可能被截断"));
        }
    }

    @Test public void localBrowserKeepsCliFailureFactsAndRelayIdentity() throws Exception {
        Fixture f = new Fixture(false);
        when(f.relay.isOnline("local-developer")).thenReturn(true);
        when(f.cli.exec(any())).thenReturn(CliResult.builder().ok(false).tool("opencli").exitCode(2)
                .stderr("login required").stdout("").durationMs(12).build());
        ToolResultPayload payload = f.run(List.of("reddit", "search", "java"));
        assertFalse(payload.isSuccess());
        assertTrue(payload.getLlmObservation().contains("login required"));
        assertTrue(payload.getLlmObservation().contains("\"exit_code\":2"));
        ArgumentCaptor<CliInvocation> captured = ArgumentCaptor.forClass(CliInvocation.class);
        verify(f.cli).exec(captured.capture());
        assertEquals("local-developer", captured.getValue().getEnv().get("OPENCLI_RELAY_VISITOR_ID"));
        assertEquals("http://127.0.0.1:8100/internal/browser/rpc", captured.getValue().getEnv().get("OPENCLI_RELAY_URL"));
        assertEquals(List.of("reddit", "search", "java", "-f", "json"), captured.getValue().getArgs());
    }

    @Test public void remoteBrowserDoesNotPersistCdpOrSensitiveOutput() throws Exception {
        Fixture f = new Fixture(true);
        when(f.sessions.isConfigured()).thenReturn(true);
        when(f.sessions.resolveForUser("local-developer")).thenReturn(AgentBrowserSession.builder()
                .cdpWsUrl("wss://secret.example/cdp").reconstructed(true).build());
        when(f.cli.exec(any())).thenReturn(CliResult.builder().ok(true).tool("opencli").exitCode(0)
                .stdout("cookie-value wss://secret.example/cdp").durationMs(2).build());
        ToolResultPayload payload = f.run(List.of("browser", "cookies"));
        assertTrue(payload.isSuccess());
        assertFalse(payload.getToolResult().contains("cookie-value"));
        assertFalse(payload.getLlmObservation().contains("wss://secret.example/cdp"));
        assertTrue(payload.getToolResult().contains("redacted"));
        ArgumentCaptor<CliInvocation> captured = ArgumentCaptor.forClass(CliInvocation.class);
        verify(f.cli).exec(captured.capture());
        assertEquals("wss://secret.example/cdp", captured.getValue().getEnv().get("OPENCLI_CDP_ENDPOINT"));
        assertTrue(captured.getValue().getUnsetEnv().contains("KERNEL_API_KEY"));
    }

    @Test public void offlineBrowserDoesNotExecuteCli() throws Exception {
        Fixture f = new Fixture(false);
        ToolResultPayload payload = f.run(List.of("list"));
        assertEquals("browser_offline", payload.getErrorCode());
        verify(f.cli, never()).exec(any());
    }

    @Test public void screenshotIsRegisteredWithoutPersistingBase64() throws Exception {
        Fixture f = new Fixture(false);
        when(f.relay.isOnline("local-developer")).thenReturn(true);
        String image = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 1});
        when(f.cli.exec(any())).thenReturn(CliResult.builder().ok(true).tool("opencli").exitCode(0)
                .stdout("{\"data\":{\"tree\":\"page tree\",\"imageBase64\":\"" + image + "\"}}")
                .durationMs(3).build());
        when(f.workspace.writable(eq(f.context), anyString())).thenAnswer(call -> {
            Path path = f.workspace.root(f.context).resolve(call.getArgument(1, String.class));
            Files.createDirectories(path.getParent());
            return path;
        });
        when(f.artifacts.registerBrowserScreenshot(eq(f.context), any(Path.class), eq("call"), eq("browser")))
                .thenReturn(Map.of("artifactId", "image-1", "name", "screenshot.png"));
        ToolResultPayload payload = f.run(List.of("browser", "screenshot"));
        assertEquals(List.of("image-1"), payload.getArtifactRefs());
        assertTrue(payload.getLlmObservation().contains("page tree"));
        assertFalse(payload.getToolResult().contains(image));
    }

    @Test public void browserScreenshotUsesWorkspaceFileReturnedByOpenCli() throws Exception {
        Fixture f = new Fixture(false);
        when(f.relay.isOnline("local-developer")).thenReturn(true);
        when(f.workspace.writable(eq(f.context), anyString())).thenAnswer(call ->
                f.workspace.root(f.context).resolve(call.getArgument(1, String.class)));
        when(f.cli.exec(any())).thenAnswer(call -> {
            CliInvocation invocation = call.getArgument(0);
            String relative = invocation.getArgs().get(invocation.getArgs().size() - 1);
            assertTrue(relative.startsWith("outputs/browser-"));
            assertTrue(relative.endsWith(".png"));
            Files.write(f.workspace.root(f.context).resolve(relative),
                    new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 1});
            return CliResult.builder().ok(true).tool("opencli").exitCode(0)
                    .stdout("Screenshot saved to: " + relative).durationMs(3).build();
        });
        when(f.artifacts.registerBrowserScreenshot(eq(f.context), any(Path.class), eq("call"), eq("browser")))
                .thenReturn(Map.of("artifactId", "image-2", "name", "browser.png"));

        ToolResultPayload payload = f.run(List.of("browser", "session", "screenshot"));
        assertEquals(List.of("image-2"), payload.getArtifactRefs());
        assertTrue(payload.getLlmObservation().contains("image-2"));
        assertFalse(payload.getLlmObservation().contains("imageBase64"));
    }

    @Test public void browserScreenshotHelpDoesNotAttemptArtifactRegistration() throws Exception {
        Fixture f = new Fixture(false);
        when(f.relay.isOnline("local-developer")).thenReturn(true);
        when(f.cli.exec(any())).thenReturn(CliResult.builder().ok(true).tool("opencli").exitCode(0)
                .stdout("Usage: opencli browser <session> screenshot [path]").build());
        ToolResultPayload payload = f.run(List.of("browser", "session", "screenshot", "--help"));
        assertTrue(payload.isSuccess());
        assertFalse(payload.getLlmObservation().contains("screenshot_error"));
        verify(f.artifacts, never()).registerBrowserScreenshot(any(), any(), any(), any());
    }

    @Test public void searchArrayDataRemainsAValidCliResult() throws Exception {
        Fixture f = new Fixture(false);
        when(f.relay.isOnline("local-developer")).thenReturn(true);
        when(f.cli.exec(any())).thenReturn(CliResult.builder().ok(true).tool("opencli").exitCode(0)
                .stdout("{\"data\":[{\"title\":\"result\"}]}").durationMs(2).build());
        ToolResultPayload payload = f.run(List.of("reddit", "search", "test"));
        assertTrue(payload.isSuccess());
        assertTrue(payload.getLlmObservation().contains("result"));
    }

    private final class Fixture {
        final CliExecutionPort cli = mock(CliExecutionPort.class);
        final BrowserRelaySocketPort relay = mock(BrowserRelaySocketPort.class);
        final AgentBrowserSessionPort sessions = mock(AgentBrowserSessionPort.class);
        final RunWorkspaceService workspace = mock(RunWorkspaceService.class);
        final WorkspaceArtifactRegistrationService artifacts = mock(WorkspaceArtifactRegistrationService.class);
        final AgentContext context = AgentContext.builder().sessionId("session").build();
        final OpenCliBrowserTool tool;

        Fixture(boolean remote) throws Exception {
            OpenCliProperties opencli = new OpenCliProperties();
            opencli.setEnabled(true);
            BrowserRelayProperties relayProperties = new BrowserRelayProperties();
            relayProperties.setInternalRpcSecret("relay-secret");
            Path root = temp.newFolder().toPath();
            when(workspace.root(context)).thenReturn(root);
            when(cli.isResolvable("opencli")).thenReturn(true);
            tool = new OpenCliBrowserTool(remote, cli, opencli, relayProperties, relay, sessions,
                    new LocalBrowserOwner(relayProperties), workspace, artifacts,
                    "http://127.0.0.1:8100/internal/browser/rpc");
        }

        ToolResultPayload run(List<String> args) {
            return (ToolResultPayload) tool.execute(ToolInvokeRequest.builder()
                    .toolName(tool.name()).toolCallId("call").arguments(Map.of("args", args)).build(), context);
        }
    }
}
