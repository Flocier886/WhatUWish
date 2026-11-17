package com.flocier.domain.agent.runtime.llm.executor;

import com.flocier.domain.agent.runtime.LlmRuntimeDependencies;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 新 LLM 运行时的执行入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmExecutor {

    private final LlmRuntimeDependencies llmRuntimeDependencies;
    private final LedgerFactService ledgerFactService;

    public LlmResponse call(LlmRequest request) {
        LlmRequest effectiveRequest = enrichLlmCallId(validateAndReturn(request));

        LlmResolvedModel resolvedModel = llmRuntimeDependencies.settingsRegistry().resolve(effectiveRequest.getModel());
        OpenAiChatModel chatModel = llmRuntimeDependencies.modelResolver().resolve(resolvedModel);
        OpenAiChatOptions options = llmRuntimeDependencies.chatOptionsFactory().build(resolvedModel, effectiveRequest);
        Prompt prompt = new Prompt(
                llmRuntimeDependencies.messageConverter().toSpringMessages(effectiveRequest.getMessages()),
                options
        );

        log.info("llm call start, modelAlias={}, model={}, requestId={}, sessionId={}, traceId={}, runId={}, scene={}, messageCount={}",
                resolvedModel.getAlias(),
                resolvedModel.getConfig().getModel(),
                attr(effectiveRequest, "requestId"),
                attr(effectiveRequest, "sessionId"),
                attr(effectiveRequest, "traceId"),
                attr(effectiveRequest, "runId"),
                attr(effectiveRequest, "scene"),
                effectiveRequest.getMessages() == null ? 0 : effectiveRequest.getMessages().size());

        LlmResponse response = llmRuntimeDependencies.responseMapper()
                .map(resolvedModel, chatModel.call(prompt), llmRuntimeDependencies.settingsRegistry().settings().getBehavior());
        LlmInvocationLedgerEntity invocation = ledgerFactService.recordLlmCall(effectiveRequest, response);
        if (response != null && invocation != null) {
            response.getMetadata().put("ledgerLlmInvocationId", invocation.getLlmInvocationId());
        }

        log.info("llm call finished, modelAlias={}, model={}, requestId={}, traceId={}, runId={}, finishReason={}, totalTokens={}, responsePreview={}",
                resolvedModel.getAlias(),
                resolvedModel.getConfig().getModel(),
                attr(effectiveRequest, "requestId"),
                attr(effectiveRequest, "traceId"),
                attr(effectiveRequest, "runId"),
                response == null ? null : response.getFinishReason(),
                response == null || response.getUsage() == null ? null : response.getUsage().getTotalTokens(),
                preview(response == null ? null : response.getText()));
        return response;
    }

    private Object attr(LlmRequest request, String key) {
        if (request == null || request.getAttributes() == null) {
            return null;
        }
        return request.getAttributes().get(key);
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private LlmRequest validateAndReturn(LlmRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("LlmRequest must not be null");
        }
        if (Boolean.TRUE.equals(request.getStream())) {
            throw new UnsupportedOperationException("Streaming is not implemented in phase 1 LLM runtime");
        }
        return request;
    }

    /**
     * 为每一次真实 LLM 调用补齐唯一 llmCallId，避免同一步多次调用在 ledger 中被错误折叠。
     */
    private LlmRequest enrichLlmCallId(LlmRequest request) {
        Map<String, Object> attributes = request.getAttributes() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getAttributes());
        attributes.putIfAbsent("llmCallId", UUID.randomUUID().toString());
        return LlmRequest.builder()
                .model(request.getModel())
                .messages(request.getMessages())
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .stream(request.getStream())
                .attributes(attributes)
                .build();
    }
}
