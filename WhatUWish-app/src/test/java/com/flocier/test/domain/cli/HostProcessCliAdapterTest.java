package com.flocier.test.domain.cli;

import com.flocier.domain.agent.adapter.port.cli.CliInvocation;
import com.flocier.domain.agent.adapter.port.cli.CliResult;
import com.flocier.infrastructure.adapter.port.HostProcessCliAdapter;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 按 master 的进程适配器测试覆盖 argv、超时、环境和脚本拒绝。 */
public class HostProcessCliAdapterTest {

    @Test
    public void capturesStdoutAndStderrWithoutShell() {
        HostProcessCliAdapter adapter = new HostProcessCliAdapter();
        assertTrue(adapter.isResolvable("node"));
        CliResult result = adapter.exec(CliInvocation.builder()
                .tool("node")
                .args(List.of("-e", "process.stdout.write('hello-cli'); process.stderr.write('err')"))
                .timeoutMs(10_000)
                .maxOutputChars(4_000)
                .capture("both")
                .build());
        assertTrue(result.isOk());
        assertEquals("hello-cli", result.getStdout());
        assertEquals("err", result.getStderr());
        assertFalse(result.isTimedOut());
    }

    @Test
    public void killsOnTimeoutAndReportsOutputTruncation() {
        HostProcessCliAdapter adapter = new HostProcessCliAdapter();
        CliResult timedOut = adapter.exec(CliInvocation.builder()
                .tool("node")
                .args(List.of("-e", "Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,5000)"))
                .timeoutMs(400)
                .maxOutputChars(1_000)
                .capture("both")
                .build());
        assertFalse(timedOut.isOk());
        assertTrue(timedOut.isTimedOut());

        CliResult truncated = adapter.exec(CliInvocation.builder()
                .tool("node")
                .args(List.of("-e", "process.stdout.write('1234567890')"))
                .timeoutMs(10_000)
                .maxOutputChars(4)
                .capture("both")
                .build());
        assertTrue(truncated.isOk());
        assertTrue(truncated.isTruncated());
        assertEquals("1234", truncated.getStdout());
    }

    @Test
    public void mergesChildEnvironmentAndRefusesPowerShellScript() {
        HostProcessCliAdapter adapter = new HostProcessCliAdapter();
        Map<String, String> environment = new HashMap<>();
        environment.put("KERNEL_API_KEY", "secret-key");
        environment.put("PATH", "keep-me");
        HostProcessCliAdapter.applyChildEnvironment(environment, CliInvocation.builder()
                .tool("node")
                .env(Map.of("OPENCLI_CDP_ENDPOINT", "wss://example.test/cdp"))
                .unsetEnv(List.of("KERNEL_API_KEY"))
                .build());
        assertFalse(environment.containsKey("KERNEL_API_KEY"));
        assertEquals("keep-me", environment.get("PATH"));
        assertEquals("wss://example.test/cdp", environment.get("OPENCLI_CDP_ENDPOINT"));
        assertFalse(adapter.isResolvable("definitely-missing-bin-xyz"));
        CliResult script = adapter.exec(CliInvocation.builder().tool("script.ps1").args(List.of()).build());
        assertFalse(script.isOk());
        assertTrue(script.getStderr().contains("PowerShell"));
    }
}
