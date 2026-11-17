import { Menu } from 'lucide-react';

export function MobileNavigation({ onOpen }: { onOpen(): void }) {
  return <button type="button" className="mobile-tool-button" onClick={onOpen} aria-label="打开 Agent 和会话导航" title="打开导航"><Menu size={18} /></button>;
}
