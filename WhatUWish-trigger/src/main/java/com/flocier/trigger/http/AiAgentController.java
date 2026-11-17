package com.flocier.trigger.http;

import com.alibaba.fastjson.JSON;
import com.flocier.api.IAiAgentService;
import com.flocier.api.dto.AiAgentResponseDTO;
import com.flocier.api.dto.ArmoryAgentRequestDTO;
import com.flocier.api.dto.AutoAgentRequestDTO;
import com.flocier.api.response.Response;
import com.flocier.application.agent.dispatch.IAgentDispatchService;
import com.flocier.application.agent.execute.AgentExecuteCommand;
import com.flocier.application.agent.stream.AgentSessionStream;
import com.flocier.application.agent.stream.ResponseBodyEmitterAgentSessionStream;
import com.flocier.application.agent.warmup.IWarmupApplicationService;
import com.flocier.domain.agent.model.vo.AiAgentVO;
import com.flocier.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class AiAgentController implements IAiAgentService {

    @Resource
    private IAgentDispatchService agentDispatchService;
    @Resource
    private IWarmupApplicationService warmupApplicationService;

    @Override
    @PostMapping("auto_agent")
    public ResponseBodyEmitter autoAgent(@RequestBody AutoAgentRequestDTO request, HttpServletResponse response) {
        log.info("autoAgent request start: {}", JSON.toJSONString(request));
        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");

            ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);
            AgentSessionStream stream = new ResponseBodyEmitterAgentSessionStream(emitter);
            AgentExecuteCommand command = AgentExecuteCommand.builder()
                    .aiAgentId(request.getAiAgentId())
                    .message(request.getMessage())
                    .sessionId(request.getSessionId())
                    .maxStep(request.getMaxStep())
                    .build();
            agentDispatchService.dispatch(command, stream);
            return emitter;
        } catch (Exception e) {
            log.error("autoAgent request failed", e);
            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter();
            try {
                errorEmitter.send("request failed: " + e.getMessage());
                errorEmitter.complete();
            } catch (Exception ex) {
                log.error("send error failed", ex);
            }
            return errorEmitter;
        }
    }

    @Override
    @PostMapping("armory_agent")
    public Response<Boolean> armoryAgent(@RequestBody ArmoryAgentRequestDTO request) {
        log.info("armoryAgent request start: {}", JSON.toJSONString(request));
        try {
            if (request == null || request.getAgentId() == null || request.getAgentId().trim().isEmpty()) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("agentId不能为空")
                        .data(false)
                        .build();
            }
            warmupApplicationService.warmupAgent(request.getAgentId());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("预热成功")
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("armoryAgent failed", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("预热失败: " + e.getMessage())
                    .data(false)
                    .build();
        }
    }

    @Override
    @GetMapping("query_available_agents")
    public Response<List<AiAgentResponseDTO>> queryAvailableAgents() {
        log.info("queryAvailableAgents request start");
        try {
            List<AiAgentVO> aiAgentVOList = warmupApplicationService.queryAvailableAgents();
            List<AiAgentResponseDTO> responseList = new ArrayList<>();
            for (AiAgentVO aiAgentVO : aiAgentVOList) {
                responseList.add(AiAgentResponseDTO.builder()
                        .agentId(aiAgentVO.getAgentId())
                        .agentName(aiAgentVO.getAgentName())
                        .description(aiAgentVO.getDescription())
                        .channel(aiAgentVO.getChannel())
                        .strategy(aiAgentVO.getStrategy())
                        .status(aiAgentVO.getStatus())
                        .build());
            }
            return Response.<List<AiAgentResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("查询成功")
                    .data(responseList)
                    .build();
        } catch (Exception e) {
            log.error("queryAvailableAgents failed", e);
            return Response.<List<AiAgentResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败: " + e.getMessage())
                    .data(new ArrayList<>())
                    .build();
        }
    }
}
