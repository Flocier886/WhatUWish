package com.flocier.domain.agent.runtime.memory;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 参考 master 的消息投影，将本项目支持的原生模型消息与数据库行相互转换。 */
@Component
public class WorkingMemoryProjector {
    /** 保留消息顺序、工具调用配对和来源键；不把运行事实或 Artifact 正文展开进投影。 */
    public List<WorkingMemoryMessage> project(List<LlmMessage> messages, String sessionId,
                                               String requestId, String runId) {
        if (messages == null) {
            return List.of();
        }
        List<WorkingMemoryMessage> rows = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message == null || message.getRole() == null) {
                continue;
            }
            WorkingMemoryMessage row = new WorkingMemoryMessage();
            row.setSessionId(sessionId);
            row.setRequestId(requestId);
            row.setRunId(runId);
            row.setSeqNo(rows.size());
            Object origin = message.getMetadata() == null ? null : message.getMetadata().get("originMessageKey");
            row.setOriginMessageKey(origin instanceof String key && !key.isBlank()
                    ? key : requestId + ":" + row.getSeqNo());
            row.setRole(message.getRole().name());
            row.setContent(message.getContent());
            row.setToolCallId(message.getToolCallId());
            row.setToolCallsJson(message.getToolCalls() == null || message.getToolCalls().isEmpty()
                    ? null : JSON.toJSONString(message.getToolCalls()));
            row.setMessageKind(switch (message.getRole()) {
                case USER -> "query";
                case TOOL -> "tool_observation";
                case ASSISTANT -> "assistant";
                case SYSTEM -> "system_note";
            });
            rows.add(row);
        }
        return rows;
    }

    /** 将 READY 消息行恢复为模型可继续使用的角色、工具调用及工具结果消息。 */
    public List<LlmMessage> hydrate(List<WorkingMemoryMessage> rows) {
        if (rows == null) {
            return List.of();
        }
        List<LlmMessage> result = new ArrayList<>();
        for (WorkingMemoryMessage row : rows) {
            if (row == null || row.getRole() == null) {
                continue;
            }
            LlmMessageRole role;
            try {
                role = LlmMessageRole.valueOf(row.getRole());
            } catch (IllegalArgumentException ignored) {
                role = LlmMessageRole.USER;
            }
            List<ToolCall> calls = row.getToolCallsJson() == null ? List.of()
                    : JSON.parseArray(row.getToolCallsJson(), ToolCall.class);
            LlmMessage message = LlmMessage.builder().role(role).content(row.getContent())
                    .toolCallId(row.getToolCallId()).toolCalls(calls == null ? List.of() : calls).build();
            if (row.getOriginMessageKey() != null) {
                message.getMetadata().put("originMessageKey", row.getOriginMessageKey());
            }
            result.add(message);
        }
        return result;
    }

    /** BUILDING 尾部可能停在并发工具批次中间，只恢复完整配对的消息前缀。 */
    public List<LlmMessage> hydrateSafePrefix(List<WorkingMemoryMessage> rows) {
        List<LlmMessage> messages = hydrate(rows);
        for (int i = 0; i < messages.size(); i++) {
            LlmMessage current = messages.get(i);
            if (current.getRole() != LlmMessageRole.ASSISTANT || current.getToolCalls() == null
                    || current.getToolCalls().isEmpty()) {
                if (current.getRole() == LlmMessageRole.TOOL) return messages.subList(0, i);
                continue;
            }
            List<String> pending = current.getToolCalls().stream()
                    .filter(call -> call != null && call.getId() != null)
                    .map(ToolCall::getId).toList();
            if (pending.size() != current.getToolCalls().size()) return messages.subList(0, i);
            java.util.Set<String> remaining = new java.util.HashSet<>(pending);
            int next = i + 1;
            while (next < messages.size() && messages.get(next).getRole() == LlmMessageRole.TOOL) {
                if (!remaining.remove(messages.get(next).getToolCallId())) return messages.subList(0, i);
                next++;
            }
            if (!remaining.isEmpty()) return messages.subList(0, i);
            i = next - 1;
        }
        return messages;
    }
}
