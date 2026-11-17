package com.flocier.domain.agent.runtime.memory;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 参考 master 的工作记忆压缩算法；裁剪时把工具调用和对应结果视为一组。 */
@Component
public class WorkingMemoryCompactor {
    /** 旧 TOOL 正文被清理后的模型可见占位符。 */
    public static final String CLEARED_TOOL_RESULT = "[Old tool result content cleared]";
    /** 已压缩历史的识别围栏，用于后续压缩时迭代更新摘要。 */
    public static final String SUMMARY_PREFIX = "[CONTEXT COMPACTION - REFERENCE ONLY]";

    /** 按消息正文及工具参数粗估 token；这是预算估算，不是模型提供的实际用量。 */
    public int estimateTokens(List<LlmMessage> messages) {
        if (messages == null) return 0;
        int sum = 0;
        for (LlmMessage message : messages) {
            if (message == null) continue;
            sum += 8 + estimateText(message.getContent());
            if (message.getToolCalls() != null) {
                for (ToolCall call : message.getToolCalls()) {
                    if (call != null && call.getFunction() != null) {
                        sum += estimateText(call.getFunction().getName())
                                + estimateText(call.getFunction().getArguments());
                    }
                }
            }
        }
        return sum;
    }

    public int estimateText(String text) {
        if (text == null) return 0;
        int ascii = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < 128) ascii++;
        return (ascii + 3) / 4 + text.length() - ascii;
    }

    /** 清理较老的 TOOL 正文，并对近期过长 TOOL 正文截断，保留角色及 toolCallId。 */
    public List<LlmMessage> microcompact(List<LlmMessage> messages, int keepRecent, int maxChars) {
        List<Integer> tools = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() == LlmMessageRole.TOOL) tools.add(i);
        }
        Set<Integer> recent = new HashSet<>(tools.subList(Math.max(0, tools.size() - Math.max(0, keepRecent)), tools.size()));
        List<LlmMessage> result = new ArrayList<>(messages);
        for (int index : tools) {
            LlmMessage old = messages.get(index);
            String content = old.getContent();
            if (!recent.contains(index) && !CLEARED_TOOL_RESULT.equals(content)) {
                result.set(index, copyWithContent(old, CLEARED_TOOL_RESULT));
            } else if (recent.contains(index) && content != null && maxChars > 0 && content.length() > maxChars) {
                result.set(index, copyWithContent(old, content.substring(0, maxChars) + "\n...[truncated tool result]"));
            }
        }
        return result;
    }

    /** 以完整工具调用组切出头部、待摘要中段和近期尾部，并确保最新 USER 留在尾部。 */
    public Window split(List<LlmMessage> messages, int keepMinTokens, int keepMinTextMessages) {
        List<Group> groups = groups(messages);
        if (groups.size() < 3) return null;
        int headEnd = Math.min(groups.size() - 2, Math.max(1, Math.min(3, groups.size() / 4)));
        if (isSummary(messages.get(0))) headEnd = 1;
        int tailStart = groups.size() - 1;
        int tailTokens = 0;
        int tailCount = 0;
        while (tailStart > headEnd + 1 && (tailTokens < keepMinTokens || tailCount < keepMinTextMessages)) {
            Group group = groups.get(tailStart);
            tailTokens += estimateTokens(messages.subList(group.start, group.end));
            tailCount += group.end - group.start;
            tailStart--;
        }
        tailStart = Math.max(headEnd + 1, tailStart);
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == LlmMessageRole.USER && !isSummary(messages.get(i))) {
                lastUser = i;
                break;
            }
        }
        for (int i = headEnd + 1; i < tailStart; i++) {
            if (lastUser >= groups.get(i).start && lastUser < groups.get(i).end) tailStart = i;
        }
        int headIndex = groups.get(headEnd - 1).end;
        int tailIndex = groups.get(tailStart).start;
        if (headIndex >= tailIndex) return null;
        return new Window(messages.subList(0, headIndex), messages.subList(headIndex, tailIndex),
                messages.subList(tailIndex, messages.size()));
    }

    /** 用新摘要替换旧摘要和中段消息，再接回头部与近期尾部。 */
    public List<LlmMessage> buildCompacted(Window window, String summary) {
        List<LlmMessage> result = new ArrayList<>(window.head());
        if (!result.isEmpty() && isSummary(result.get(0))) result.remove(0);
        result.add(0, LlmMessage.builder().role(LlmMessageRole.USER)
                .content(SUMMARY_PREFIX + "\n" + summary + "\n[END OF CONTEXT SUMMARY - CONTINUE WITH THE LATEST USER TASK]").build());
        result.addAll(window.tail());
        return sanitizeToolProtocol(result);
    }

    /** 摘要后仍超预算时，按完整调用组删最早历史，保留摘要和最新用户任务。 */
    public List<LlmMessage> dropOldestToFit(List<LlmMessage> messages, int targetTokens) {
        List<LlmMessage> result = new ArrayList<>(messages);
        while (estimateTokens(result) >= targetTokens) {
            List<Group> grouped = groups(result);
            int latestUser = -1;
            for (int i = result.size() - 1; i >= 0; i--) {
                if (result.get(i).getRole() == LlmMessageRole.USER && !isSummary(result.get(i))) {
                    latestUser = i;
                    break;
                }
            }
            Group removable = null;
            for (Group group : grouped) {
                if (group.start == 0 && isSummary(result.get(0))) continue;
                if (latestUser >= group.start && latestUser < group.end) continue;
                if (latestUser >= 0 && group.start > latestUser) continue;
                removable = group;
                break;
            }
            if (removable == null) break;
            result.subList(removable.start, removable.end).clear();
        }
        return sanitizeToolProtocol(result);
    }

    /** 去除无对应调用的 TOOL，以及无对应结果的工具调用，避免无效模型消息序列。 */
    public List<LlmMessage> sanitizeToolProtocol(List<LlmMessage> messages) {
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmMessageRole.ASSISTANT && message.getToolCalls() != null) {
                for (ToolCall call : message.getToolCalls()) {
                    if (call != null && call.getId() != null) calls.add(call.getId());
                }
            } else if (message.getRole() == LlmMessageRole.TOOL && message.getToolCallId() != null) {
                results.add(message.getToolCallId());
            }
        }
        List<LlmMessage> clean = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmMessageRole.TOOL && !calls.contains(message.getToolCallId())) continue;
            if (message.getRole() == LlmMessageRole.ASSISTANT && message.getToolCalls() != null
                    && !message.getToolCalls().isEmpty()) {
                List<ToolCall> kept = message.getToolCalls().stream()
                        .filter(call -> call != null && results.contains(call.getId())).toList();
                if (kept.size() != message.getToolCalls().size()) {
                    clean.add(LlmMessage.builder().role(message.getRole())
                            .content(message.getContent() == null && kept.isEmpty() ? "(tool call removed)" : message.getContent())
                            .toolCalls(kept).metadata(message.getMetadata()).build());
                    continue;
                }
            }
            clean.add(message);
        }
        return clean;
    }

    public boolean isSummary(LlmMessage message) {
        return message != null && message.getContent() != null
                && message.getContent().startsWith(SUMMARY_PREFIX);
    }

    /** 将待摘要消息序列化为限长文本，防止摘要输入无限增长。 */
    public String serialize(List<LlmMessage> messages, int maxChars) {
        StringBuilder text = new StringBuilder();
        if (messages == null || maxChars <= 0) return "";
        for (LlmMessage message : messages) {
            if (text.length() >= maxChars) break;
            String label = "[" + message.getRole() + "] ";
            text.append(label, 0, Math.min(label.length(), maxChars - text.length()));
            if (text.length() >= maxChars) break;
            String content = message.getContent() == null ? "" : message.getContent();
            text.append(content, 0, Math.min(content.length(), Math.min(4000, maxChars - text.length())));
            if (text.length() < maxChars) text.append('\n');
        }
        return text.toString();
    }

    /** ASSISTANT 工具调用及紧随其后的 TOOL 结果共用一个不可拆分边界。 */
    private List<Group> groups(List<LlmMessage> messages) {
        List<Group> groups = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            int end = index + 1;
            LlmMessage current = messages.get(index);
            if (current.getRole() == LlmMessageRole.ASSISTANT && current.getToolCalls() != null
                    && !current.getToolCalls().isEmpty()) {
                Set<String> pending = new HashSet<>();
                for (ToolCall call : current.getToolCalls()) if (call != null && call.getId() != null) pending.add(call.getId());
                while (end < messages.size() && !pending.isEmpty()) {
                    LlmMessage next = messages.get(end);
                    if (next.getRole() != LlmMessageRole.TOOL) break;
                    pending.remove(next.getToolCallId());
                    end++;
                }
            }
            groups.add(new Group(index, end));
            index = end;
        }
        return groups;
    }

    private LlmMessage copyWithContent(LlmMessage old, String content) {
        return LlmMessage.builder().role(old.getRole()).content(content)
                .toolCallId(old.getToolCallId()).toolCalls(old.getToolCalls()).metadata(old.getMetadata()).build();
    }

    private record Group(int start, int end) {}

    /** 历史压缩窗口：头部保留、中段摘要、尾部原样保留。 */
    public record Window(List<LlmMessage> head, List<LlmMessage> middle, List<LlmMessage> tail) {}
}
