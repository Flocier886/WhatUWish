package com.flocier.domain.agent.runtime;

import com.flocier.domain.agent.adapter.repository.IAgentRepository;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.client.AiClientRuntimeRegistry;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * workflow 域与 warmup 域共享的运行时依赖聚合器。
 * 后续新增的运行时服务应优先依赖这里，而不是继续分散地直接注入零散 Bean。
 */
@Component
@RequiredArgsConstructor
public class RuntimeDependencies {

    private final IAgentRepository repository;
    private final ReactorConfig reactorConfig;
    private final AiClientRuntimeRegistry aiClientRuntimeRegistry;
    private final LLM llm;
    private final LlmRuntimeDependencies llmRuntimeDependencies;
    private final MemoryFacade memoryFacade;
    private final SkillRegistry skillRegistry;

    public IAgentRepository repository() {
        return repository;
    }

    public ReactorConfig reactorConfig() {
        return reactorConfig;
    }

    public AiClientRuntimeRegistry aiClientRuntimeRegistry() {
        return aiClientRuntimeRegistry;
    }

    public LLM llm() {
        return llm;
    }

    public LlmRuntimeDependencies llmRuntimeDependencies() {
        return llmRuntimeDependencies;
    }

    public MemoryFacade memoryFacade() {
        return memoryFacade;
    }

    public SkillRegistry skillRegistry() {
        return skillRegistry;
    }

    public SkillRuntimeOptions skillRuntimeOptions() {
        return reactorConfig.getSkillRuntimeOptions();
    }
}
