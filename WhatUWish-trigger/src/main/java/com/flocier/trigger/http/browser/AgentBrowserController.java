package com.flocier.trigger.http.browser;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import com.flocier.api.response.Response;
import com.flocier.domain.agent.browser.AgentBrowserApplicationService;
import com.flocier.domain.agent.browser.LocalBrowserOwner;
import com.flocier.domain.agent.adapter.port.AgentBrowserLiveView;
import com.flocier.domain.agent.adapter.port.AgentBrowserSessionStatus;
import com.flocier.types.enums.ResponseCode;

/** 工作台远端浏览器入口：查看状态、取得实时画面、重置会话。 */
@RestController
@RequestMapping("/api/v1/agent-browser")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AgentBrowserController {

    private final AgentBrowserApplicationService agentBrowserApplicationService;
    private final LocalBrowserOwner owner;

    public AgentBrowserController(AgentBrowserApplicationService agentBrowserApplicationService,
                                  LocalBrowserOwner owner) {
        this.agentBrowserApplicationService = agentBrowserApplicationService;
        this.owner = owner;
    }

    /** 可能创建远端浏览器；画面地址只在本次响应中交给前端打开。 */
    @PostMapping("/ensure")
    public ResponseEntity<Response<AgentBrowserLiveView>> ensure() {
        AgentBrowserLiveView liveView = agentBrowserApplicationService.ensure(
                owner.requireOwnerId());
        Response<AgentBrowserLiveView> response = Response.<AgentBrowserLiveView>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(liveView)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @GetMapping("/status")
    public Response<AgentBrowserSessionStatus> status() {
        AgentBrowserSessionStatus status = agentBrowserApplicationService.status(
                owner.requireOwnerId());
        return Response.<AgentBrowserSessionStatus>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(status)
                .build();
    }

    @DeleteMapping("/session")
    public Response<Void> reset() {
        agentBrowserApplicationService.reset(owner.requireOwnerId());
        return Response.<Void>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .build();
    }
}
