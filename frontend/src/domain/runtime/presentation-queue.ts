import type { RuntimeEvent } from './types';

export interface PresentationSnapshot { textByEventId: Record<string, string>; pendingEventIds: string[] }

/** 只控制已到达文本的绘制节奏，不改写 Runtime Event 事实。 */
export class PresentationQueue {
  private readonly revealed = new Map<string, number>();

  tick(events: RuntimeEvent[], settle = false): PresentationSnapshot {
    const textByEventId: Record<string, string> = {};
    const pendingEventIds: string[] = [];
    events.filter((event) => event.eventType === 'LLM_RESPONSE').forEach((event) => {
      const text = String((event.payload as { text?: string }).text ?? '');
      const previous = this.revealed.get(event.eventId) ?? 0;
      const next = settle ? text.length : Math.min(text.length, previous + Math.max(12, Math.ceil(text.length / 20)));
      this.revealed.set(event.eventId, next);
      textByEventId[event.eventId] = text.slice(0, next);
      if (next < text.length) pendingEventIds.push(event.eventId);
    });
    return { textByEventId, pendingEventIds };
  }
}
