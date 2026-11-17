import { useEffect, useRef, useState } from 'react';
import { PresentationQueue, type PresentationSnapshot } from '@/domain/runtime/presentation-queue';
import type { RuntimeEvent } from '@/domain/runtime/types';

const empty: PresentationSnapshot = { textByEventId: {}, pendingEventIds: [] };

export function usePresentationQueue(events: RuntimeEvent[], settle: boolean): PresentationSnapshot {
  const queue = useRef(new PresentationQueue());
  const [snapshot, setSnapshot] = useState<PresentationSnapshot>(() => settle ? queue.current.tick(events, true) : empty);
  useEffect(() => {
    let frame = 0;
    let timer = 0;
    const update = () => {
      const next = queue.current.tick(events, settle || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
      setSnapshot(next);
      if (next.pendingEventIds.length > 0) timer = window.setTimeout(() => { frame = window.requestAnimationFrame(update); }, 32);
    };
    frame = window.requestAnimationFrame(update);
    return () => { window.cancelAnimationFrame(frame); window.clearTimeout(timer); };
  }, [events, settle]);
  return snapshot;
}
