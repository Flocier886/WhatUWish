package com.flocier.trigger.http.browser;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import com.flocier.api.response.Response;
import com.flocier.domain.agent.browser.BrowserPairingView;
import com.flocier.domain.agent.browser.BrowserRelayApplicationService;
import com.flocier.domain.agent.browser.LocalBrowserOwner;
import com.flocier.domain.agent.browser.model.BrowserConnectionStatus;
import com.flocier.types.agent.config.BrowserRelayProperties;
import com.flocier.types.enums.ResponseCode;

import java.util.Map;

/** 工作台本机浏览器入口：配对、短码认领、连接状态和主动断开。owner 由服务端固定配置。 */
@RestController
@ConditionalOnProperty(prefix = "reactor.browser-relay", name = "enabled", havingValue = "true")
@RequestMapping({"/api/v1/browser", "/api/agent/browser"})
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class BrowserPairingController {

    private final BrowserRelayApplicationService browserRelayApplicationService;
    private final BrowserRelayProperties properties;
    private final LocalBrowserOwner owner;

    public BrowserPairingController(BrowserRelayApplicationService browserRelayApplicationService,
                                    BrowserRelayProperties properties, LocalBrowserOwner owner) {
        this.browserRelayApplicationService = browserRelayApplicationService;
        this.properties = properties;
        this.owner = owner;
    }

    /** 返回 token 与 WebSocket 地址，供页面通过 postMessage 交给浏览器扩展。 */
    @PostMapping("/pairing")
    public Response<BrowserPairingView> pairing(HttpServletRequest request) {
        BrowserPairingView view = browserRelayApplicationService.createPairing(
                owner.requireOwnerId(),
                buildRelayUrl(request)
        );
        return Response.<BrowserPairingView>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(view)
                .build();
    }

    @PostMapping("/claim")
    public Response<BrowserPairingView> claim(@RequestBody Map<String, Object> body) {
        try {
            String code = body == null || body.get("code") == null ? null : String.valueOf(body.get("code"));
            BrowserPairingView view = browserRelayApplicationService.claim(code);
            return Response.<BrowserPairingView>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(view)
                    .build();
        } catch (Exception e) {
            return Response.<BrowserPairingView>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @GetMapping("/status")
    public Response<BrowserConnectionStatus> status() {
        BrowserConnectionStatus status = browserRelayApplicationService.status(owner.requireOwnerId());
        return Response.<BrowserConnectionStatus>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(status)
                .build();
    }

    @PostMapping("/disconnect")
    public Response<Void> disconnect() {
        browserRelayApplicationService.revokeUser(owner.requireOwnerId());
        return Response.<Void>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .build();
    }

    /** 根据当前请求地址生成扩展可连接的 ws/wss 地址。 */
    private String buildRelayUrl(HttpServletRequest request) {
        String proto = StringUtils.defaultIfBlank(request.getHeader("X-Forwarded-Proto"),
                request.isSecure() ? "https" : "http");
        String ws = "https".equalsIgnoreCase(proto) ? "wss" : "ws";
        String host = StringUtils.defaultIfBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        return ws + "://" + host + properties.getPath();
    }
}
