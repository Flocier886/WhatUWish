import { LoaderCircle } from 'lucide-react';
import type { RunSummary } from '@/domain/runtime/types';
import { RunStatusBadge } from '@/features/runs/RunStatusBadge';

export function RunHistoryItem({ run, selected, loading, onSelect }: { run: RunSummary; selected: boolean; loading: boolean; onSelect(): void }) {
  return <button type="button" className={`run-history-item ${selected ? 'run-history-selected' : ''}`} onClick={onSelect} aria-pressed={selected}><span><strong>{run.task}</strong><small>{run.startedAt ? new Date(run.startedAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' }) : '刚刚'} · {run.isHistorical ? '历史回放' : '当前会话'}</small></span>{loading ? <LoaderCircle className="spin" size={16} /> : <RunStatusBadge status={run.status} />}</button>;
}
