import { History, RefreshCw } from 'lucide-react';
import type { RunSummary } from '@/domain/runtime/types';
import { EmptyState } from '@/components/shared/EmptyState';
import { RunHistoryItem } from './RunHistoryItem';

export function RunHistoryList({ runs, selectedRunId, loadingRunId, onSelect, onRefresh }: { runs: RunSummary[]; selectedRunId?: string; loadingRunId?: string; onSelect(runId: string): void; onRefresh(): void }) {
  return <section className="run-history" aria-label="历史 Run"><div className="panel-heading"><div><span className="eyebrow"><History size={13} /> RUN HISTORY</span><h2>会话回放</h2></div><button className="icon-button" type="button" title="刷新历史 Run" aria-label="刷新历史 Run" onClick={onRefresh}><RefreshCw size={16} /></button></div>{runs.length === 0 ? <EmptyState title="暂无历史 Run" description="完成一次任务后，会话中的 Run 会出现在这里。" /> : <div className="run-history-list">{runs.map((run) => <RunHistoryItem key={run.runId} run={run} selected={run.runId === selectedRunId} loading={loadingRunId === run.runId} onSelect={() => onSelect(run.runId)} />)}</div>}</section>;
}
