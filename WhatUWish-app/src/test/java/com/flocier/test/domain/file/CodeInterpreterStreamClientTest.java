package com.flocier.test.domain.file;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.rich.codeinterpreter.CodeInterpreterStreamClient;
import com.flocier.domain.agent.runtime.file.MaterialFileAccessResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CodeInterpreterStreamClientTest {
    @Test
    public void acceptsOrderedStepsAndTerminalResult() throws Exception {
        Map<String, Object> step = Map.of("type", "step", "sequence", 1, "step", 1,
                "sessionId", "session-1", "runId", "run-1", "toolCallId", "call-1",
                "thought", "计算", "code", "print(2)", "observation", "2");
        Map<String, Object> result = Map.of("type", "result", "sequence", 2, "status", "success",
                "sessionId", "session-1", "runId", "run-1", "toolCallId", "call-1",
                "stepCount", 1, "conclusion", "合计为 2", "durationMs", 12,
                "materialOutputs", List.of(Map.of("artifactId", "artifact-1", "relativePath", "outputs/结果.csv",
                        "fileName", "结果.csv", "sha256", "abc")));
        HttpServer server = server(frame("step", step) + frame("result", result));
        try {
            List<Map<String, Object>> received = new ArrayList<>();
            var response = client().stream(base(server), request(), context(), prepared(), received::add);
            Assert.assertTrue(response.isSuccess());
            Assert.assertEquals(2, received.size());
            Assert.assertEquals("合计为 2", response.getOutput());
            Assert.assertEquals(1, response.getMaterialOutputs().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsMismatchedSessionBeforePublishing() throws Exception {
        Map<String, Object> step = Map.of("type", "step", "sequence", 1, "step", 1,
                "sessionId", "another", "runId", "run-1", "toolCallId", "call-1");
        HttpServer server = server(frame("step", step));
        try {
            List<Map<String, Object>> received = new ArrayList<>();
            var response = client().stream(base(server), request(), context(), prepared(), received::add);
            Assert.assertFalse(response.isSuccess());
            Assert.assertEquals("code_interpreter_stream_failed", response.getErrorCode());
            Assert.assertTrue(received.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/material/code-interpreter/stream", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }

    private String frame(String type, Map<String, Object> value) {
        return "event: " + type + "\ndata: " + JSON.toJSONString(value) + "\n\n";
    }

    private String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private CodeInterpreterStreamClient client() {
        var client = new CodeInterpreterStreamClient();
        ReflectionTestUtils.setField(client, "bridgeToken", "test-only");
        return client;
    }

    private AgentContext context() {
        var context = AgentContext.builder().sessionId("session-1").requestId("request-1").build();
        context.setAttribute("runId", "run-1");
        return context;
    }

    private ToolInvokeRequest request() {
        return ToolInvokeRequest.builder().toolName("code_interpreter").toolCallId("call-1")
                .sessionId("session-1").requestId("request-1").arguments(Map.of("task", "计算")).build();
    }

    private MaterialFileAccessResolver.Prepared prepared() {
        return new MaterialFileAccessResolver.Prepared(Map.of("task", "计算", "__authorizedInputPaths", List.of()),
                List.of(), "C:/test/session-session-1");
    }
}
