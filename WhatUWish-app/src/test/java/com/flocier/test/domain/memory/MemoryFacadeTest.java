package com.flocier.test.domain.memory;

import com.flocier.domain.agent.model.entity.LedgerMemoryBuildCommandEntity;
import com.flocier.domain.agent.model.entity.SessionMemoryView;
import com.flocier.domain.agent.model.entity.ShortTermMemoryQueryResultEntity;
import com.flocier.domain.agent.runtime.memory.LedgerMemoryExtractor;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.agent.runtime.memory.MemoryInjectionBudgetPolicy;
import com.flocier.domain.agent.service.IMemoryService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

public class MemoryFacadeTest {

    @Test
    public void shouldNotLoadRedisWhenBuildingMemoryPrompt() {
        IMemoryService memoryService = Mockito.mock(IMemoryService.class);
        LedgerMemoryExtractor extractor = Mockito.mock(LedgerMemoryExtractor.class);
        MemoryInjectionBudgetPolicy policy = Mockito.mock(MemoryInjectionBudgetPolicy.class);
        Mockito.when(memoryService.queryShortTermMemory(ArgumentMatchers.any()))
                .thenReturn(ShortTermMemoryQueryResultEntity.builder().summary("Redis 内容不应进入 prompt").build());
        Mockito.when(extractor.extract(ArgumentMatchers.any(LedgerMemoryBuildCommandEntity.class)))
                .thenReturn(SessionMemoryView.builder().sessionId("session-ledger").build());
        Mockito.when(policy.apply(ArgumentMatchers.any(SessionMemoryView.class)))
                .thenReturn(SessionMemoryView.builder().injectionText("连续 Ledger 历史").build());

        MemoryFacade facade = new MemoryFacade(memoryService, extractor, policy);
        String prompt = facade.buildMemoryPrompt("session-ledger", "run-current", "request-current");

        Assert.assertEquals("连续 Ledger 历史", prompt);
        Mockito.verify(memoryService, Mockito.never()).queryShortTermMemory(ArgumentMatchers.any());
    }

    @Test
    public void shouldReturnEmptyPromptInsteadOfRedisFallbackWhenLedgerIsEmpty() {
        IMemoryService memoryService = Mockito.mock(IMemoryService.class);
        LedgerMemoryExtractor extractor = Mockito.mock(LedgerMemoryExtractor.class);
        MemoryInjectionBudgetPolicy policy = Mockito.mock(MemoryInjectionBudgetPolicy.class);
        Mockito.when(memoryService.queryShortTermMemory(ArgumentMatchers.any()))
                .thenReturn(ShortTermMemoryQueryResultEntity.builder().summary("Redis fallback must stay isolated").build());
        Mockito.when(extractor.extract(ArgumentMatchers.any(LedgerMemoryBuildCommandEntity.class)))
                .thenReturn(SessionMemoryView.builder().sessionId("session-empty").build());
        Mockito.when(policy.apply(ArgumentMatchers.any(SessionMemoryView.class)))
                .thenReturn(SessionMemoryView.builder().injectionText("").build());

        MemoryFacade facade = new MemoryFacade(memoryService, extractor, policy);

        Assert.assertEquals("", facade.buildMemoryPrompt("session-empty", "run-current", "request-current"));
        Mockito.verify(memoryService, Mockito.never()).queryShortTermMemory(ArgumentMatchers.any());
    }
}
