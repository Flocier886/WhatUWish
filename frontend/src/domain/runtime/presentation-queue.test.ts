import { describe, expect, it } from 'vitest';
import { PresentationQueue } from './presentation-queue';
import type { RuntimeEvent } from './types';

const event: RuntimeEvent = {
  schemaVersion: 'v1', eventId: 'llm-1', sequence: 1, eventType: 'LLM_RESPONSE', occurredAt: '2026-07-25T00:00:00Z',
  sessionId: 'session-1', runId: 'run-1', requestId: 'request-1', agentId: 'agent-1', payload: { llmInvocationId: 'llm-1', text: '这是已经到达但需要渐进呈现的模型文本。' },
};

describe('PresentationQueue', () => {
  it('只逐步显示已经到达的文本，并在终态时完整 flush', () => {
    const queue = new PresentationQueue();
    const first = queue.tick([event]);
    expect(first.textByEventId[event.eventId].length).toBeGreaterThan(0);
    expect(first.textByEventId[event.eventId].length).toBeLessThan((event.payload as { text: string }).text.length);
    expect(first.pendingEventIds).toEqual([event.eventId]);
    const complete = queue.tick([event], true);
    expect(complete.textByEventId[event.eventId]).toBe((event.payload as { text: string }).text);
    expect(complete.pendingEventIds).toEqual([]);
  });
});
