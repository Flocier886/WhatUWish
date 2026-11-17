package com.flocier.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.entity.ShortTermMemoryAppendCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryCommandEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryResultEntity;
import com.flocier.domain.agent.model.entity.ShortTermMemoryRecordEntity;
import com.flocier.domain.agent.model.vo.*;
import com.flocier.infrastructure.persistent.IMemorySummaryService;
import com.flocier.infrastructure.persistent.IRedisService;
import com.flocier.infrastructure.dao.*;
import com.flocier.infrastructure.dao.po.*;
import com.flocier.types.common.Constants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RDeque;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.flocier.domain.agent.model.vo.AiAgentEnumVO.*;

@Slf4j
@Repository
public class AgentRepository implements IAgentRepository {
    @Resource
    private IAiAgentDao aiAgentDao;

    @Resource
    private IAiAgentFlowConfigDao aiAgentFlowConfigDao;

    @Resource
    private IAiAgentTaskScheduleDao aiAgentTaskScheduleDao;

    @Resource
    private IAiClientAdvisorDao aiClientAdvisorDao;

    @Resource
    private IAiClientApiDao aiClientApiDao;

    @Resource
    private IAiClientConfigDao aiClientConfigDao;

    @Resource
    private IAiClientDao aiClientDao;

    @Resource
    private IAiClientModelDao aiClientModelDao;

    @Resource
    private IAiClientRagOrderDao aiClientRagOrderDao;

    @Resource
    private IAiClientSystemPromptDao aiClientSystemPromptDao;

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Resource
    private IRedisService redisService;
    @Resource
    private IMemorySummaryService memorySummaryService;

    @Value("${memory.short-term.default-window-size:8}")
    private Integer defaultWindowSize;
    @Value("${memory.short-term.retain-ratio:0.75}")
    private Double retainRatio;
    @Value("${memory.short-term.summary-max-length:4000}")
    private Integer summaryMaxLength;
    @Value("${memory.short-term.ttl-seconds:259200}")
    private Long shortTermMemoryTtlSeconds;

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientApiVO> result = new ArrayList<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的modelId
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);

            for (AiClientConfig config : configs) {
                if (AI_CLIENT_MODEL.getCode().equals(config.getTargetType()) && config.getStatus() == 1) {
                    String modelId = config.getTargetId();

                    // 2. 通过modelId查询模型配置，获取apiId
                    AiClientModel model = aiClientModelDao.queryByModelId(modelId);
                    if (model != null && model.getStatus() == 1) {
                        String apiId = model.getApiId();

                        // 3. 通过apiId查询API配置信息
                        AiClientApi apiConfig = aiClientApiDao.queryByApiId(apiId);
                        if (apiConfig != null && apiConfig.getStatus() == 1) {
                            // 4. 转换为VO对象
                            AiClientApiVO apiVO = AiClientApiVO.builder()
                                    .apiId(apiConfig.getApiId())
                                    .baseUrl(apiConfig.getBaseUrl())
                                    .apiKey(apiConfig.getApiKey())
                                    .completionsPath(apiConfig.getCompletionsPath())
                                    .embeddingsPath(apiConfig.getEmbeddingsPath())
                                    .build();

                            // 避免重复添加相同的API配置
                            if (result.stream().noneMatch(vo -> vo.getApiId().equals(apiVO.getApiId()))) {
                                result.add(apiVO);
                            }
                        }
                    }
                }
            }
        }

        return result;

    }

    @Override
    public List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList) {
        if(clientIdList==null || clientIdList.isEmpty()){
            return List.of();
        }
        List<AiClientModelVO>result=new ArrayList<>();
        for (String clientId:clientIdList){
            List<AiClientConfig> aiClientConfigs=aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(),clientId);

            for (AiClientConfig config:aiClientConfigs){
                if(config.getTargetType().equals(AI_CLIENT_MODEL.getCode()) && config.getStatus()==1){
                    String modelId=config.getTargetId();
                    AiClientModel model=aiClientModelDao.queryByModelId(modelId);
                    if(model!=null && model.getStatus()==1){
                        //查询相关mcp配置
                        List<AiClientConfig> toolMcpConfigs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT_MODEL.getCode(), modelId);
                        List<String> toolMcpIds = new ArrayList<>();

                        for (AiClientConfig toolMcpConfig : toolMcpConfigs) {
                            if (AI_CLIENT_TOOL_MCP.getCode().equals(toolMcpConfig.getTargetType()) && toolMcpConfig.getStatus() == 1) {
                                toolMcpIds.add(toolMcpConfig.getTargetId());
                            }
                        }

                        // 3. 转换为VO对象
                        AiClientModelVO modelVO = AiClientModelVO.builder()
                                .modelId(model.getModelId())
                                .apiId(model.getApiId())
                                .modelName(model.getModelName())
                                .modelType(model.getModelType())
                                .toolMcpIds(toolMcpIds)
                                .build();

                        // 避免重复添加相同的模型配置
                        if (result.stream().noneMatch(vo -> vo.getModelId().equals(modelVO.getModelId()))) {
                            result.add(modelVO);
                        }

                    }
                }
            }
        }
        return result;
    }

    @Override
    public List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientToolMcpVO> result = new ArrayList<>();
        Set<String> processedMcpIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的model配置
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);

            for (AiClientConfig config : configs) {
                if (AI_CLIENT_MODEL.getCode().equals(config.getTargetType()) && config.getStatus() == 1) {
                    String modelId = config.getTargetId();
                    List<AiClientConfig> modelConfigs=aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT_MODEL.getCode(),modelId);
                    for (AiClientConfig modelConfig : modelConfigs) {
                        if (AI_CLIENT_TOOL_MCP.getCode().equals(modelConfig.getTargetType()) && modelConfig.getStatus() == 1) {
                            String mcpId = modelConfig.getTargetId();

                            // 避免重复处理相同的mcpId
                            if (processedMcpIds.contains(mcpId)) {
                                continue;
                            }
                            processedMcpIds.add(mcpId);

                            // 3. 通过mcpId查询ai_client_tool_mcp表获取MCP工具配置
                            AiClientToolMcp toolMcp = aiClientToolMcpDao.queryByMcpId(mcpId);
                            if (toolMcp != null && toolMcp.getStatus() == 1) {
                                // 4. 转换为VO对象
                                AiClientToolMcpVO mcpVO = AiClientToolMcpVO.builder()
                                        .mcpId(toolMcp.getMcpId())
                                        .mcpName(toolMcp.getMcpName())
                                        .transportType(toolMcp.getTransportType())
                                        .transportConfig(toolMcp.getTransportConfig())
                                        .requestTimeout(toolMcp.getRequestTimeout())
                                        .build();

                                String transportConfig = toolMcp.getTransportConfig();
                                String transportType = toolMcp.getTransportType();

                                try {
                                    if ("sse".equals(transportType)) {
                                        // 解析SSE配置
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        AiClientToolMcpVO.TransportConfigSse transportConfigSse = objectMapper.readValue(transportConfig, AiClientToolMcpVO.TransportConfigSse.class);
                                        mcpVO.setTransportConfigSse(transportConfigSse);
                                    } else if ("stdio".equals(transportType)) {
                                        // 解析STDIO配置
                                        Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdio = JSON.parseObject(transportConfig,
                                                new TypeReference<>() {
                                                });

                                        AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = new AiClientToolMcpVO.TransportConfigStdio();
                                        transportConfigStdio.setStdio(stdio);

                                        mcpVO.setTransportConfigStdio(transportConfigStdio);
                                    } else if ("streamableHttp".equals(transportType)){
                                        // 解析StreamableHttp配置
                                        ObjectMapper objectMapper=new ObjectMapper();
                                        AiClientToolMcpVO.TransportConfigStreamableHttp transportConfigStreamableHttp=objectMapper.readValue(transportConfig,AiClientToolMcpVO.TransportConfigStreamableHttp.class);
                                        mcpVO.setTransportConfigStreamableHttp(transportConfigStreamableHttp);
                                    }
                                } catch (Exception e) {
                                    log.error("解析传输配置失败: {}", e.getMessage(), e);
                                }
                                result.add(mcpVO);
                            }
                        }
                    }

                }
            }
        }

        return result;

    }

    @Override
    public List<AiClientToolMcpVO> queryEnabledAiClientToolMcpVOList() {
        List<AiClientToolMcp> enabled = aiClientToolMcpDao.queryEnabledMcps();
        if (enabled == null || enabled.isEmpty()) {
            return List.of();
        }
        List<AiClientToolMcpVO> result = new ArrayList<>();
        for (AiClientToolMcp item : enabled) {
            if (item == null || StringUtils.isBlank(item.getMcpId())) {
                continue;
            }
            try {
                result.add(toMcpVO(item));
            } catch (Exception exception) {
                log.error("解析启用 MCP 配置失败: mcpId={}, transportType={}, reason={}",
                        item.getMcpId(), item.getTransportType(), exception.getMessage(), exception);
            }
        }
        return List.copyOf(result);
    }

    private AiClientToolMcpVO toMcpVO(AiClientToolMcp item) throws Exception {
        AiClientToolMcpVO result = AiClientToolMcpVO.builder()
                .mcpId(item.getMcpId())
                .mcpName(item.getMcpName())
                .transportType(item.getTransportType())
                .transportConfig(item.getTransportConfig())
                .requestTimeout(item.getRequestTimeout())
                .build();
        String transportType = item.getTransportType();
        String transportConfig = item.getTransportConfig();
        if ("sse".equalsIgnoreCase(transportType)) {
            result.setTransportConfigSse(new ObjectMapper().readValue(
                    transportConfig, AiClientToolMcpVO.TransportConfigSse.class));
        } else if ("stdio".equalsIgnoreCase(transportType)) {
            Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdio = JSON.parseObject(
                    transportConfig, new TypeReference<>() { });
            AiClientToolMcpVO.TransportConfigStdio config = new AiClientToolMcpVO.TransportConfigStdio();
            config.setStdio(stdio);
            result.setTransportConfigStdio(config);
        } else if ("streamableHttp".equalsIgnoreCase(transportType)
                || "streamable_http".equalsIgnoreCase(transportType)) {
            result.setTransportConfigStreamableHttp(new ObjectMapper().readValue(
                    transportConfig, AiClientToolMcpVO.TransportConfigStreamableHttp.class));
        } else {
            throw new IllegalArgumentException("不支持的 MCP transportType: " + transportType);
        }
        return result;
    }

    @Override
    public List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientSystemPromptVO> result = new ArrayList<>();
        Set<String> processedPromptIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的prompt配置
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);

            for (AiClientConfig config : configs) {
                if ("prompt".equals(config.getTargetType()) && config.getStatus() == 1) {
                    String promptId = config.getTargetId();

                    // 避免重复处理相同的promptId
                    if (processedPromptIds.contains(promptId)) {
                        continue;
                    }
                    processedPromptIds.add(promptId);

                    // 2. 通过promptId查询ai_client_system_prompt表获取系统提示词配置
                    AiClientSystemPrompt systemPrompt = aiClientSystemPromptDao.queryByPromptId(promptId);
                    if (systemPrompt != null && systemPrompt.getStatus() == 1) {
                        // 3. 转换为VO对象
                        AiClientSystemPromptVO promptVO = AiClientSystemPromptVO.builder()
                                .promptId(systemPrompt.getPromptId())
                                .promptName(systemPrompt.getPromptName())
                                .promptContent(systemPrompt.getPromptContent())
                                .description(systemPrompt.getDescription())
                                .build();

                        result.add(promptVO);
                    }
                }
            }
        }

        return result;

    }

    @Override
    public Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList) {
        List<AiClientSystemPromptVO> aiClientSystemPromptVOS = AiClientSystemPromptVOByClientIds(clientIdList);
        if(aiClientSystemPromptVOS==null || aiClientSystemPromptVOS.isEmpty()){
            return Collections.emptyMap();
        }
        return aiClientSystemPromptVOS.stream()
                .map(prompt->AiClientSystemPromptVO.builder()
                        .promptId(prompt.getPromptId())
                        .promptContent(prompt.getPromptContent())
                        .build())
                .collect(Collectors.toMap(
                        AiClientSystemPromptVO::getPromptId,  //key
                        prompt->prompt,  //value
                        (existing,replacement)->existing  //若有重复，只保留一个
                ));
    }

    @Override
    public List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientAdvisorVO> result = new ArrayList<>();
        Set<String> processedAdvisorIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 查询客户端相关的advisor配置
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId("client", clientId);

            for (AiClientConfig config : configs) {
                if (config.getStatus() != 1 || !"advisor".equals(config.getTargetType())) {
                    continue;
                }

                String advisorId = config.getTargetId();
                if (processedAdvisorIds.contains(advisorId)) {
                    continue;
                }
                processedAdvisorIds.add(advisorId);

                // 2. 查询advisor详细信息
                AiClientAdvisor aiClientAdvisor = aiClientAdvisorDao.queryByAdvisorId(advisorId);
                if (aiClientAdvisor == null || aiClientAdvisor.getStatus() != 1) {
                    continue;
                }

                // 3. 解析extParam中的配置
                AiClientAdvisorVO.ChatMemory chatMemory = null;
                AiClientAdvisorVO.RagAnswer ragAnswer = null;
                AiClientAdvisorVO.RagAnswerAdvance ragAnswerAdvance = null;

                String extParam = aiClientAdvisor.getExtParam();
                if (extParam != null && !extParam.trim().isEmpty()) {
                    try {
                        if ("ChatMemory".equals(aiClientAdvisor.getAdvisorType())) {
                            // 解析chatMemory配置
                            chatMemory = JSON.parseObject(extParam, AiClientAdvisorVO.ChatMemory.class);
                        } else if ("RagAnswer".equals(aiClientAdvisor.getAdvisorType())) {
                            // 解析ragAnswer配置
                            ragAnswer = JSON.parseObject(extParam, AiClientAdvisorVO.RagAnswer.class);
                        } else if ("RagAnswerAdvance".equals(aiClientAdvisor.getAdvisorType())) {
                            // 解析高级Rag：query改写 / 可选BM25 / 可选rerank 等（未配置的字段见 FastJSON 反序列化规则）
                            ragAnswerAdvance = JSON.parseObject(extParam, AiClientAdvisorVO.RagAnswerAdvance.class);
                        }
                    } catch (Exception e) {
                        // 解析失败时忽略，使用默认值null
                    }
                }

                // 4. 构建AiClientAdvisorVO对象
                AiClientAdvisorVO advisorVO = AiClientAdvisorVO.builder()
                        .advisorId(aiClientAdvisor.getAdvisorId())
                        .advisorName(aiClientAdvisor.getAdvisorName())
                        .advisorType(aiClientAdvisor.getAdvisorType())
                        .orderNum(aiClientAdvisor.getOrderNum())
                        .chatMemory(chatMemory)
                        .ragAnswer(ragAnswer)
                        .ragAnswerAdvance(ragAnswerAdvance)
                        .build();

                result.add(advisorVO);
            }
        }

        return result;

    }

    @Override
    public List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientVO> result = new ArrayList<>();
        Set<String> processedClientIds = new HashSet<>();

        for (String clientId : clientIdList) {
            if (processedClientIds.contains(clientId)) {
                continue;
            }
            processedClientIds.add(clientId);

            // 1. 查询客户端基本信息
            AiClient aiClient = aiClientDao.queryByClientId(clientId);
            if (aiClient == null || aiClient.getStatus() != 1) {
                continue;
            }

            // 2. 查询客户端相关配置
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId("client", clientId);

            String modelId = null;
            List<String> promptIdList = new ArrayList<>();
            List<String> mcpIdList = new ArrayList<>();
            List<String> advisorIdList = new ArrayList<>();

            for (AiClientConfig config : configs) {
                if (config.getStatus() != 1) {
                    continue;
                }

                switch (config.getTargetType()) {
                    case "model":
                        modelId = config.getTargetId();
                        break;
                    case "prompt":
                        promptIdList.add(config.getTargetId());
                        break;
                    case "tool_mcp":
                        mcpIdList.add(config.getTargetId());
                        break;
                    case "advisor":
                        advisorIdList.add(config.getTargetId());
                        break;
                }
            }

            // 3. 构建AiClientVO对象
            AiClientVO aiClientVO = AiClientVO.builder()
                    .clientId(aiClient.getClientId())
                    .clientName(aiClient.getClientName())
                    .description(aiClient.getDescription())
                    .modelId(modelId)
                    .promptIdList(promptIdList)
                    .mcpIdList(mcpIdList)
                    .advisorIdList(advisorIdList)
                    .build();

            result.add(aiClientVO);
        }

        return result;

    }

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList) {
        if (modelIdList == null || modelIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientApiVO> result = new ArrayList<>();

        for (String modelId : modelIdList) {
            // 1. 通过modelId查询模型配置，获取apiId
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                String apiId = model.getApiId();

                // 2. 通过apiId查询API配置信息
                AiClientApi apiConfig = aiClientApiDao.queryByApiId(apiId);
                if (apiConfig != null && apiConfig.getStatus() == 1) {
                    // 3. 转换为VO对象
                    AiClientApiVO apiVO = AiClientApiVO.builder()
                            .apiId(apiConfig.getApiId())
                            .baseUrl(apiConfig.getBaseUrl())
                            .apiKey(apiConfig.getApiKey())
                            .completionsPath(apiConfig.getCompletionsPath())
                            .embeddingsPath(apiConfig.getEmbeddingsPath())
                            .build();

                    // 避免重复添加相同的API配置
                    if (result.stream().noneMatch(vo -> vo.getApiId().equals(apiVO.getApiId()))) {
                        result.add(apiVO);
                    }
                }
            }
        }

        return result;

    }

    @Override
    public List<AiClientModelVO> queryAiClientModelVOListByModelIds(List<String> modelIdList) {
        if (modelIdList == null || modelIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientModelVO> result = new ArrayList<>();

        for (String modelId : modelIdList) {
            // 通过modelId查询模型配置
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                // 转换为VO对象
                AiClientModelVO modelVO = AiClientModelVO.builder()
                        .modelId(model.getModelId())
                        .apiId(model.getApiId())
                        .modelName(model.getModelName())
                        .modelType(model.getModelType())
                        .build();

                // 避免重复添加相同的模型配置
                if (result.stream().noneMatch(vo -> vo.getModelId().equals(modelVO.getModelId()))) {
                    result.add(modelVO);
                }
            }
        }

        return result;

    }

    @Override
    public Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId) {
        if (aiAgentId==null || aiAgentId.trim().isEmpty()){
            return Map.of();
        }
        try {
            List<AiAgentFlowConfig> flowConfigs=aiAgentFlowConfigDao.queryByAgentId(aiAgentId);
            if (flowConfigs == null || flowConfigs.isEmpty()) {
                return Map.of();
            }
            Map<String,AiAgentClientFlowConfigVO>aiAgentClientFlowConfigVOMap=new HashMap<>();
            for (AiAgentFlowConfig flowConfig:flowConfigs){
                AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                        .clientId(flowConfig.getClientId())
                        .clientName(flowConfig.getClientName())
                        .clientType(flowConfig.getClientType())
                        .sequence(flowConfig.getSequence())
                        .stepPrompt(flowConfig.getStepPrompt())
                        .build();
                aiAgentClientFlowConfigVOMap.put(configVO.getClientType(),configVO);
            }
            return aiAgentClientFlowConfigVOMap;

        }catch (NumberFormatException e){
            log.error("Invalid aiAgentId format: {}", aiAgentId, e);
            return Map.of();
        }catch (Exception e){
            log.error("Query ai agent client flow config failed, aiAgentId: {}", aiAgentId, e);
            return Map.of();
        }
    }

    @Override
    public AiAgentVO queryAiAgentByAgentId(String aiAgentId) {
        AiAgent aiAgent = aiAgentDao.queryByAgentId(aiAgentId);

        return AiAgentVO.builder()
                .agentId(aiAgent.getAgentId())
                .agentName(aiAgent.getAgentName())
                .description(aiAgent.getDescription())
                .channel(aiAgent.getChannel())
                .strategy(aiAgent.getStrategy())
                .status(aiAgent.getStatus())
                .build();

    }

    @Override
    public List<AiAgentClientFlowConfigVO> queryAiClientsByAgentId(String aiAgentId) {
        List<AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOS=new ArrayList<>();

        List<AiAgentFlowConfig>flowConfigs=aiAgentFlowConfigDao.queryByAgentId(aiAgentId);
        for (AiAgentFlowConfig flowConfig : flowConfigs) {
            AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                    .clientId(flowConfig.getClientId())
                    .clientName(flowConfig.getClientName())
                    .clientType(flowConfig.getClientType())
                    .sequence(flowConfig.getSequence())
                    .stepPrompt(flowConfig.getStepPrompt())
                    .build();

            aiAgentClientFlowConfigVOS.add(configVO);
        }
        return aiAgentClientFlowConfigVOS;
    }

    @Override
    public List<AiAgentVO> queryAvailableAgents() {
        List<AiAgent> aiAgents = aiAgentDao.queryEnabledAgents();
        List<AiAgentVO> aiAgentVOS = new ArrayList<>();
        for (AiAgent aiAgent : aiAgents) {
            aiAgentVOS.add(AiAgentVO.builder()
                    .agentId(aiAgent.getAgentId())
                    .agentName(aiAgent.getAgentName())
                    .description(aiAgent.getDescription())
                    .channel(aiAgent.getChannel())
                    .strategy(aiAgent.getStrategy())
                    .status(aiAgent.getStatus())
                    .build());
        }
        return aiAgentVOS;
    }

    @Override
    public List<AiAgentClientFlowConfigVO> queryAiAgentClientsByAgentId(String agentId) {
        List<AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOS = new ArrayList<>();

        List<AiAgentFlowConfig> flowConfigs = aiAgentFlowConfigDao.queryByAgentId(agentId);
        for (AiAgentFlowConfig flowConfig : flowConfigs) {
            AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                    .clientId(flowConfig.getClientId())
                    .clientName(flowConfig.getClientName())
                    .clientType(flowConfig.getClientType())
                    .sequence(flowConfig.getSequence())
                    .stepPrompt(flowConfig.getStepPrompt())
                    .build();

            aiAgentClientFlowConfigVOS.add(configVO);
        }

        return aiAgentClientFlowConfigVOS;

    }

    @Override
    public void createTagOrder(AiRagOrderVO aiRagOrderVO) {
        AiClientRagOrder aiRagOrder = new AiClientRagOrder();
        aiRagOrder.setRagName(aiRagOrderVO.getRagName());
        aiRagOrder.setKnowledgeTag(aiRagOrderVO.getKnowledgeTag());
        aiRagOrder.setRagId(RandomStringUtils.randomNumeric(5));
        aiRagOrder.setStatus(1);
        aiClientRagOrderDao.insert(aiRagOrder);
    }

    @Override
    public void appendShortTermMemory(ShortTermMemoryAppendCommandEntity commandEntity) {
        String sessionId = commandEntity.getSessionId();
        int windowSize = resolveWindowSize(commandEntity.getWindowSize());
        int retainCount = resolveRetainCount(windowSize);
        String recordsKey = recordsKey(sessionId);
        String summaryKey = summaryKey(sessionId);
        String roundKey = roundKey(sessionId);
        String lockKey = lockKey(sessionId);

        RLock lock = redisService.getLock(lockKey);
        lock.lock();
        try {
            RAtomicLong roundAtomicLong = redisService.getAtomicLong(roundKey);
            long round = roundAtomicLong.incrementAndGet();

            RDeque<ShortTermMemoryRecordEntity> recordDeque = redisService.getDeque(recordsKey);
            recordDeque.addLast(ShortTermMemoryRecordEntity.builder()
                    .round(round)
                    .userQuestion(commandEntity.getUserQuestion())
                    .finalAnswer(commandEntity.getFinalAnswer())
                    .timestamp(System.currentTimeMillis())
                    .build());

            compressAndSummarizeIfNeeded(sessionId, recordDeque, summaryKey, windowSize, retainCount);
            refreshTtl(recordsKey, summaryKey, roundKey);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ShortTermMemoryQueryResultEntity queryShortTermMemory(ShortTermMemoryQueryCommandEntity commandEntity) {
        String sessionId = commandEntity.getSessionId();
        String recordsKey = recordsKey(sessionId);
        String summaryKey = summaryKey(sessionId);
        String roundKey = roundKey(sessionId);

        RDeque<ShortTermMemoryRecordEntity> recordDeque = redisService.getDeque(recordsKey);
        List<ShortTermMemoryRecordEntity> allRecords = new ArrayList<>(recordDeque.readAll());
        int recentRounds = resolveRecentRounds(commandEntity.getRecentRounds(), allRecords.size());
        int startIndex = Math.max(allRecords.size() - recentRounds, 0);
        List<ShortTermMemoryRecordEntity> recentRecords = allRecords.subList(startIndex, allRecords.size());

        String summary = redisService.getValue(summaryKey);
        if (summary == null) {
            summary = "";
        }

        Long totalRounds = redisService.getAtomicLong(roundKey).get();
        if (totalRounds == null) {
            totalRounds = 0L;
        }

        return ShortTermMemoryQueryResultEntity.builder()
                .sessionId(sessionId)
                .totalRounds(totalRounds)
                .summary(summary)
                .recentRecords(new ArrayList<>(recentRecords))
                .build();
    }

    private void compressAndSummarizeIfNeeded(String sessionId, RDeque<ShortTermMemoryRecordEntity> recordDeque, String summaryKey, int windowSize, int retainCount) {
        int currentSize = recordDeque.size();
        if (currentSize < windowSize) {
            return;
        }

        List<ShortTermMemoryRecordEntity> overflowRecords = new ArrayList<>();
        while (recordDeque.size() > retainCount) {
            ShortTermMemoryRecordEntity removed = recordDeque.pollFirst();
            if (removed != null) {
                overflowRecords.add(removed);
            }
        }
        if (overflowRecords.isEmpty()) {
            return;
        }

        String existedSummary = redisService.getValue(summaryKey);
        String llmSummary = memorySummaryService.summarize(existedSummary, overflowRecords);
        String mergedSummary = mergeSummary(existedSummary, llmSummary, buildSummary(overflowRecords));
        redisService.setValue(summaryKey, truncateSummary(mergedSummary));
        log.debug("短期记忆触发批量压缩 sessionId:{} window:{} retain:{} current:{} compressed:{} remain:{}",
                sessionId, windowSize, retainCount, currentSize, overflowRecords.size(), recordDeque.size());
    }

    private void refreshTtl(String recordsKey, String summaryKey, String roundKey) {
        long ttl = resolveTtlSeconds();
        redisService.expire(recordsKey, ttl, TimeUnit.SECONDS);
        redisService.expire(summaryKey, ttl, TimeUnit.SECONDS);
        redisService.expire(roundKey, ttl, TimeUnit.SECONDS);
    }

    private int resolveWindowSize(Integer windowSize) {
        if (windowSize == null || windowSize <= 0) {
            return defaultWindowSize;
        }
        return windowSize;
    }

    private int resolveRetainCount(int windowSize) {
        double ratio = retainRatio == null ? 0.75D : retainRatio;
        if (ratio <= 0D || ratio >= 1D) {
            ratio = 0.75D;
        }
        int retainCount = (int) Math.floor(windowSize * ratio);
        if (retainCount <= 0) {
            return 1;
        }
        if (retainCount >= windowSize) {
            return windowSize - 1;
        }
        return retainCount;
    }

    private int resolveRecentRounds(Integer recentRounds, int allSize) {
        if (recentRounds == null || recentRounds <= 0) {
            return allSize;
        }
        return Math.min(recentRounds, allSize);
    }

    private long resolveTtlSeconds() {
        if (shortTermMemoryTtlSeconds == null || shortTermMemoryTtlSeconds <= 0) {
            return 259200L;
        }
        return shortTermMemoryTtlSeconds;
    }

    private String buildSummary(List<ShortTermMemoryRecordEntity> records) {
        StringBuilder summaryBuilder = new StringBuilder();
        for (ShortTermMemoryRecordEntity record : records) {
            summaryBuilder.append("第").append(record.getRound()).append("轮: ")
                    .append("Q=").append(record.getUserQuestion())
                    .append(" | A=").append(record.getFinalAnswer())
                    .append("\n");
        }
        return summaryBuilder.toString();
    }

    private String mergeSummary(String oldSummary, String llmSummary, String fallbackSummary) {
        if (StringUtils.isNotBlank(llmSummary)) {
            return llmSummary;
        }
        String newSummary = fallbackSummary;
        if (StringUtils.isBlank(oldSummary)) {
            return newSummary;
        }
        return oldSummary + newSummary;
    }

    private String truncateSummary(String summary) {
        if (StringUtils.isBlank(summary)) {
            return "";
        }
        int maxLength = summaryMaxLength == null || summaryMaxLength <= 0 ? 4000 : summaryMaxLength;
        if (summary.length() <= maxLength) {
            return summary;
        }
        return summary.substring(summary.length() - maxLength);
    }

    private String recordsKey(String sessionId) {
        return Constants.MEMORY_RECORDS+sessionId;
    }

    private String summaryKey(String sessionId) {
        return Constants.MEMORY_SUMMARY+sessionId;
    }

    private String roundKey(String sessionId) {
        return Constants.MEMORY_ROUND+sessionId;
    }

    private String lockKey(String sessionId) {
        return Constants.MEMORY_LOCK+sessionId;
    }
}
