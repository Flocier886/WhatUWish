import type { RuntimeEvent, ToolResultPayload } from '@/domain/runtime/types';
import { applyUiPatchesChecked } from './applyUiPatch';

export interface GenUiCanvas {
  id: string;
  runId: string;
  sessionId: string;
  requestId: string;
  tree?: Record<string, unknown>;
  patchCount: number;
  issue?: string;
}

/** Replay 与 Live 只消费后端确认的同一版本展示投影。 */
export function projectGenUiCanvases(events: RuntimeEvent[]): GenUiCanvas[] {
  const canvases: GenUiCanvas[] = [];
  const ordered = [...events].sort((a, b) => a.sequence - b.sequence);
  const seen = new Set<string>();
  for (const event of ordered) {
    if (seen.has(event.eventId) || event.eventType !== 'TOOL_RESULT') continue;
    seen.add(event.eventId);
    const result = event.payload as ToolResultPayload;
    if (result.status !== 'SUCCEEDED' || !result.presentation) continue;
    const { kind, schemaVersion, data } = result.presentation;
    if (kind !== 'genui.tree.v1' && kind !== 'genui.patch.v1') continue;
    if (schemaVersion !== '1') {
      canvases.push({ id: result.toolInvocationId, runId: event.runId, sessionId: event.sessionId,
        requestId: event.requestId, patchCount: 0, issue: '当前客户端不支持该画布版本。' });
      continue;
    }
    if (kind === 'genui.tree.v1') {
      if (!data || typeof data.tree !== 'object' || !data.tree || Array.isArray(data.tree)) continue;
      const id = typeof data.canvasId === 'string' && data.canvasId ? data.canvasId : result.toolInvocationId;
      const existing = canvases.find((canvas) => canvas.id === id);
      if (existing) {
        existing.tree = data.tree as Record<string, unknown>;
        existing.patchCount = 0;
        existing.issue = undefined;
      } else {
        canvases.push({ id, runId: event.runId, sessionId: event.sessionId,
          requestId: event.requestId, tree: data.tree as Record<string, unknown>, patchCount: 0 });
      }
      continue;
    }
    const id = typeof data?.canvasId === 'string' ? data.canvasId : undefined;
    const target = id ? [...canvases].reverse().find((canvas) => canvas.id === id) : canvases.at(-1);
    if (!target || !target.tree) {
      canvases.push({ id: result.toolInvocationId, runId: event.runId, sessionId: event.sessionId,
        requestId: event.requestId, patchCount: 0, issue: '没有找到该补丁对应的画布起点。' });
      continue;
    }
    if (!Array.isArray(data?.patches) || data.patches.length === 0) { target.issue = '画布补丁无效。'; continue; }
    try {
      const tree = applyUiPatchesChecked(target.tree, data.patches as Array<{ op: string; path: string; value?: unknown }>);
      if (!tree || typeof tree !== 'object' || !('root' in tree)) throw new Error('invalid root');
      target.tree = tree;
      target.patchCount += data.patches.length;
    } catch {
      target.issue = '画布补丁无法应用，已保留上一版。';
    }
  }
  return canvases;
}
