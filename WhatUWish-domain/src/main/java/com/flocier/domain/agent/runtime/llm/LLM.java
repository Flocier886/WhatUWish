package com.flocier.domain.agent.runtime.llm;

import com.flocier.domain.agent.runtime.llm.executor.LlmExecutor;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskExecutor;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * LLM 统一入口。
 * <p>
 * 当前同时承担两类职责：
 * 1. 兼容 fixed/flow/auto 链路基于 ChatClient 的直接调用。
 * 2. 对新 LLM 运行时暴露领域化的 request/response 调用入口。
 */
@Component
@RequiredArgsConstructor
public class LLM {

    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    private final LlmExecutor llmExecutor;
    private final ToolAskExecutor toolAskExecutor;

    /** 兼容旧链路的直接 ChatClient 调用方式。 */
    public String call(ChatClient chatClient,
                       String userPrompt,
                       Consumer<ChatClient.ChatClientRequestSpec> customizer,
                       String sessionId,
                       Integer retrieveSize) {
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(userPrompt)
                .advisors(advisor -> {
                    if (sessionId != null && !sessionId.isBlank()) {
                        advisor.param(ChatMemory.CONVERSATION_ID, sessionId);
                    }
                    if (retrieveSize != null) {
                        advisor.param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, retrieveSize);
                    }
                });
        if (customizer != null) {
            customizer.accept(requestSpec);
        }
        return requestSpec.call().content();
    }

    /** 新 LLM 运行时的领域化调用入口。 */
    public LlmResponse call(LlmRequest request) {
        return llmExecutor.call(request);
    }

    /** 新 LLM 运行时的快捷文本调用入口。 */
    public String callText(LlmRequest request) {
        return call(request).getText();
    }

    public ToolAskResponse toolAsk(ToolAskRequest request) {
        return toolAskExecutor.execute(request);
    }
}
