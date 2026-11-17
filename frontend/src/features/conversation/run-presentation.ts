import type { LlmResponsePayload, RunSummary, RuntimeEvent } from '@/domain/runtime/types';

export type RunPhase = 'IDLE' | 'THINKING' | 'USING_TOOL' | 'SUMMARIZING' | 'COMPLETED' | 'FAILED';

export interface RunPresentation {
  phase: RunPhase;
  statusLabel?: string;
  processCount: number;
  finalText: string;
  finalEventId?: string;
  isStreaming: boolean;
  isFinal: boolean;
}

const summaryScenes = new Set(['summary', 'final', 'result']);

function isSummaryResponse(event: RuntimeEvent): boolean {
  if (event.eventType !== 'LLM_RESPONSE') return false;
  const scene = String((event.payload as LlmResponsePayload).scene ?? '').trim().toLowerCase();
  return summaryScenes.has(scene);
}

function statusForEvent(event: RuntimeEvent | undefined, run: RunSummary): Pick<RunPresentation, 'phase' | 'statusLabel'> {
  if (run.status === 'FAILED') return { phase: 'FAILED', statusLabel: '本次运行未能完成' };
  if (run.status === 'COMPLETED') return { phase: 'COMPLETED', statusLabel: '已完成' };
  if (run.status === 'WAITING_USER_INPUT') return { phase: 'IDLE', statusLabel: '等待用户操作桌面' };
  if (event && isSummaryResponse(event)) return { phase: 'SUMMARIZING', statusLabel: '正在整理最终回答' };
  if (event?.eventType === 'TOOL_RESULT') return { phase: 'USING_TOOL', statusLabel: '正在分析工具返回的信息' };
  return { phase: 'THINKING', statusLabel: '正在分析你的问题' };
}

/**
 * 将完整 Runtime Event 事实投影为对话展示模型。
 * 原始事件仍由 reducer 保存；这里仅决定哪些信息进入主回答、状态条和过程区。
 */
export function deriveRunPresentation(events: RuntimeEvent[], run: RunSummary): RunPresentation {
  const ordered = [...events].sort((left, right) => left.sequence - right.sequence);
  const llmResponses = ordered.filter((event) => event.eventType === 'LLM_RESPONSE');
  const summaryResponse = [...llmResponses].reverse().find(isSummaryResponse);
  const visibleResponse = summaryResponse ?? llmResponses.at(-1);
  const finalText = run.status === 'WAITING_USER_INPUT' ? ''
    : run.summary || String((visibleResponse?.payload as LlmResponsePayload | undefined)?.text ?? '');
  const latestProcessEvent = ordered.at(-1);
  const status = statusForEvent(summaryResponse ?? latestProcessEvent, run);

  return {
    ...status,
    processCount: run.summary
      ? llmResponses.length
      : llmResponses.filter((event) => event.eventId !== visibleResponse?.eventId).length,
    finalText,
    finalEventId: run.summary || run.status === 'WAITING_USER_INPUT' ? undefined : visibleResponse?.eventId,
    isStreaming: Boolean(visibleResponse) && !run.summary && !run.error && !run.isHistorical,
    isFinal: Boolean(run.summary) || run.status === 'COMPLETED',
  };
}
