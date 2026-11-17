package com.flocier.infrastructure.adapter.port;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopSessionPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 桌面会话专用桥接；不将 noVNC URL 放进模型观察。 */
@Component
public class PythonDesktopSessionAdapter implements DesktopSessionPort {
    @Value("${conversation.material.python-service:http://localhost:18081}") private String baseUrl;
    @Value("${conversation.material.bridge-token:}") private String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public OpenedDesktop open(String requestId, String sessionId, String ownerKey, int ttlSeconds) {
        JSONObject result = post("open", requestId, sessionId, ownerKey, ttlSeconds);
        String url = result.getString("url");
        Double holdUntil = result.getDouble("holdUntil");
        if (url == null || !url.startsWith("https://") || holdUntil == null)
            throw new IllegalStateException("桌面服务未返回可用的 noVNC 地址");
        return new OpenedDesktop(url, holdUntil);
    }

    @Override
    public void close(String requestId, String sessionId, String ownerKey) {
        post("close", requestId, sessionId, ownerKey, 600);
    }

    private JSONObject post(String action, String requestId, String sessionId, String ownerKey, int ttlSeconds) {
        if (token == null || token.isBlank()) throw new IllegalStateException("桌面桥接密钥尚未配置");
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/api/v1/material/desktop-session/" + action;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("X-Material-Bridge-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of(
                            "requestId", requestId, "sessionId", sessionId, "ownerKey", ownerKey,
                            "ttlSeconds", ttlSeconds)))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("桌面服务返回状态 " + response.statusCode());
            return JSON.parseObject(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("桌面请求被中断", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("桌面服务不可用", e);
        }
    }
}
