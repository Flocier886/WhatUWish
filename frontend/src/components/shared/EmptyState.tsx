import type { ReactNode } from 'react';
import { CircleAlert, Inbox } from 'lucide-react';

export function EmptyState({ title, description, tone = 'neutral', action }: { title: string; description: string; tone?: 'neutral' | 'warning'; action?: ReactNode }) {
  const Icon = tone === 'warning' ? CircleAlert : Inbox;
  return <section className={`empty-state ${tone === 'warning' ? 'empty-state-warning' : ''}`} aria-live="polite">
    <Icon aria-hidden="true" size={20} />
    <div><h3>{title}</h3><p>{description}</p>{action}</div>
  </section>;
}
