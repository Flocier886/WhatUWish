package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.model.vo.AiAgentClientFlowConfigVO;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.client.AiClientRuntimeRegistry;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.agent.service.IMemoryService;
import com.flocier.domain.agent.service.execute.fixed.FixedAgentExecuteStrategy;
import com.flocier.domain.ledger.service.LedgerFactService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Field;
import java.util.List;

public class FixedAgentExecuteStrategyTest {

    @Test
    public void shouldPassLedgerAttributesIntoLlmRequest() throws Exception {
        IAgentRepository repository = Mockito.mock(IAgentRepository.class);
        AiClientRuntimeRegistry aiClientRuntimeRegistry = Mockito.mock(AiClientRuntimeRegistry.class);
        LLM llm = Mockito.mock(LLM.class);
        IMemoryService memoryService = Mockito.mock(IMemoryService.class);
        LedgerFactService ledgerFactService = Mockito.mock(LedgerFactService.class);

        Mockito.when(repository.queryAiClientsByAgentId("agent-fixed")).thenReturn(List.of(
                AiAgentClientFlowConfigVO.builder()
                        .clientId("model-fixed")
                        .clientName("fixed")
                        .sequence(1)
                        .build()
        ));
        Mockito.when(aiClientRuntimeRegistry.getRequiredChatClient("model-fixed"))
                .thenReturn(Mockito.mock(ChatClient.class));
        Mockito.when(llm.callText(Mockito.any(LlmRequest.class))).thenReturn("final answer");

        FixedAgentExecuteStrategy strategy = new FixedAgentExecuteStrategy();
        inject(strategy, "repository", repository);
        inject(strategy, "aiClientRuntimeRegistry", aiClientRuntimeRegistry);
        inject(strategy, "llm", llm);
        inject(strategy, "memoryFacade", new MemoryFacade(memoryService));
        inject(strategy, "ledgerFactService", ledgerFactService);

        AgentContext context = AgentContext.builder()
                .requestId("req-fixed")
                .sessionId("session-fixed")
                .traceId("trace-fixed")
                .build();
        context.setAttribute("runId", "run-fixed");

        strategy.execute(ExecuteCommandEntity.builder()
                .aiAgentId("agent-fixed")
                .message("请帮我总结")
                .sessionId("session-fixed")
                .maxStep(3)
                .build(), null, context);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        Mockito.verify(llm).callText(requestCaptor.capture());
        Mockito.verify(ledgerFactService).recordRunStarted(context, "agent-fixed", "fixed");
        LlmRequest llmRequest = requestCaptor.getValue();

        Assert.assertNotNull(llmRequest);
        Assert.assertEquals("model-fixed", llmRequest.getModel());
        Assert.assertEquals("fixed_execute", llmRequest.getAttributes().get("scene"));
        Assert.assertEquals("agent-fixed", llmRequest.getAttributes().get("agentId"));
        Assert.assertEquals("session-fixed", llmRequest.getAttributes().get("sessionId"));
        Assert.assertEquals("req-fixed", llmRequest.getAttributes().get("requestId"));
        Assert.assertEquals("trace-fixed", llmRequest.getAttributes().get("traceId"));
        Assert.assertEquals("run-fixed", llmRequest.getAttributes().get("runId"));
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
