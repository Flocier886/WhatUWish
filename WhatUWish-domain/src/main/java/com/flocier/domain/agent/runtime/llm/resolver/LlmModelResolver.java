package com.flocier.domain.agent.runtime.llm.resolver;

import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import org.springframework.ai.openai.OpenAiChatModel;

/**
 * 根据新 LLM 运行时配置解析可调用模型。
 */
public interface LlmModelResolver {

    OpenAiChatModel resolve(LlmResolvedModel resolvedModel);
}
