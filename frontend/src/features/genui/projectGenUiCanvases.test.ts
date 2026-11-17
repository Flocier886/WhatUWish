import { describe, expect, it } from 'vitest';
import type { RuntimeEvent, ToolResultPayload } from '@/domain/runtime/types';
import { projectGenUiCanvases } from './projectGenUiCanvases';

function result(sequence: number, presentation: ToolResultPayload['presentation'], status: ToolResultPayload['status'] = 'SUCCEEDED'): RuntimeEvent {
  return { schemaVersion: 'v1', eventId: `event-${sequence}`, sequence, eventType: 'TOOL_RESULT',
    occurredAt: '2026-11-12T00:00:00Z', sessionId: 'session', runId: 'run', requestId: 'request', agentId: 'agent',
    payload: { toolInvocationId: `tool-${sequence}`, toolName: presentation?.kind === 'genui.patch.v1' ? 'emit_ui_patch' : 'emit_ui_tree', status, observation: '', presentation } };
}

describe('projectGenUiCanvases', () => {
  it('replays ordered tree and patch facts without mutating the original tree', () => {
    const tree = { schemaVersion: '1', root: { kind: 'Text', props: { value: '原文' }, children: [] } };
    const events = [result(2, { kind: 'genui.patch.v1', schemaVersion: '1', data: { canvasId: 'a', patches: [{ op: 'replace', path: '/root/props/value', value: '更新' }] } }),
      result(1, { kind: 'genui.tree.v1', schemaVersion: '1', data: { canvasId: 'a', tree } })];
    const [canvas] = projectGenUiCanvases(events);
    expect((canvas.tree!.root as { props: { value: string } }).props.value).toBe('更新');
    expect(canvas.patchCount).toBe(1);
    expect(tree.root.props.value).toBe('原文');
    expect(projectGenUiCanvases(events)[0].tree).toEqual(canvas.tree);
  });

  it('does not invent a canvas for failed or orphan patches', () => {
    expect(projectGenUiCanvases([result(1, { kind: 'genui.patch.v1', schemaVersion: '1', data: { patches: [{ op: 'add', path: '/root', value: {} }] } })])[0].issue).toMatch(/没有找到/u);
    expect(projectGenUiCanvases([result(1, { kind: 'genui.tree.v1', schemaVersion: '1', data: { tree: { root: {} } } }, 'FAILED')])).toEqual([]);
  });

  it('keeps the previous tree when a patch points outside the tree', () => {
    const tree = { root: { kind: 'Text', props: { value: '保留' } } };
    const [canvas] = projectGenUiCanvases([
      result(1, { kind: 'genui.tree.v1', schemaVersion: '1', data: { canvasId: 'a', tree } }),
      result(2, { kind: 'genui.patch.v1', schemaVersion: '1', data: { canvasId: 'a', patches: [{ op: 'replace', path: '/root/missing/value', value: '坏补丁' }] } }),
    ]);
    expect(canvas.tree).toEqual(tree);
    expect(canvas.patchCount).toBe(0);
    expect(canvas.issue).toMatch(/保留上一版/u);
  });

  it('replaces a repeated canvas id with its latest published tree', () => {
    const canvases = projectGenUiCanvases([
      result(1, { kind: 'genui.tree.v1', schemaVersion: '1', data: { canvasId: 'same', tree: { root: { kind: 'Card', props: { title: '旧' } } } } }),
      result(2, { kind: 'genui.tree.v1', schemaVersion: '1', data: { canvasId: 'same', tree: { root: { kind: 'Card', props: { title: '新' } } } } }),
    ]);
    expect(canvases).toHaveLength(1);
    expect((canvases[0].tree!.root as { props: { title: string } }).props.title).toBe('新');
  });

  it('reports an unsupported presentation version without rendering its tree', () => {
    const [canvas] = projectGenUiCanvases([result(1, {
      kind: 'genui.tree.v1', schemaVersion: '2', data: { canvasId: 'future', tree: { root: { kind: 'Text' } } },
    })]);
    expect(canvas.tree).toBeUndefined();
    expect(canvas.issue).toMatch(/不支持该画布版本/u);
  });
});
