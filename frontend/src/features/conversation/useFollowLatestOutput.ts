import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';

const threshold = 72;

function scrollToLatest(node: HTMLElement) {
  if (typeof node.scrollTo === 'function') node.scrollTo({ top: node.scrollHeight, behavior: 'smooth' });
  else node.scrollTop = node.scrollHeight;
}

export function useFollowLatestOutput(containerRef: RefObject<HTMLElement | null>, changeKey: string) {
  const [isNearBottom, setIsNearBottom] = useState(true);
  const [hasUnreadOutput, setHasUnreadOutput] = useState(false);
  const frame = useRef(0);
  const onScroll = useCallback(() => {
    const node = containerRef.current;
    if (!node) return;
    const nearBottom = node.scrollHeight - node.scrollTop - node.clientHeight < threshold;
    setIsNearBottom(nearBottom);
    if (nearBottom) setHasUnreadOutput(false);
  }, [containerRef]);
  useEffect(() => {
    const node = containerRef.current;
    if (!node) return;
    if (!isNearBottom) { setHasUnreadOutput(true); return; }
    window.cancelAnimationFrame(frame.current);
    frame.current = window.requestAnimationFrame(() => scrollToLatest(node));
    return () => window.cancelAnimationFrame(frame.current);
  }, [changeKey, containerRef, isNearBottom]);
  const returnToLatest = useCallback(() => {
    const node = containerRef.current;
    if (!node) return;
    scrollToLatest(node);
    setIsNearBottom(true);
    setHasUnreadOutput(false);
  }, [containerRef]);
  return { onScroll, hasUnreadOutput, returnToLatest };
}
