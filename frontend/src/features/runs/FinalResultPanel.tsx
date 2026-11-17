import { Bot, FileCheck2, Sparkles } from 'lucide-react';
import type { ArtifactReference, RunSummary } from '@/domain/runtime/types';
import { SafeMarkdown } from '@/components/shared/SafeMarkdown';
import { ArtifactReferenceList } from '@/features/artifacts/ArtifactReferenceList';

export function FinalResultPanel({ run, artifacts }: { run?: RunSummary; artifacts: ArtifactReference[] }) {
  if (!run || (!run.summary && !run.error)) return null;
  return <section className={`final-result ${run.error ? 'final-result-failed' : ''}`} aria-label="最终结果">
    <div className="message-avatar agent-avatar"><Bot size={16} /></div><div className="final-result-content"><div className="panel-heading"><div><span className="eyebrow">{run.error ? <FileCheck2 size={13} /> : <Sparkles size={13} />}{run.error ? '执行未完成' : 'Agent 回答'}</span><h2>{run.error ? '运行未完成' : '最终结论'}</h2></div></div>
    {run.error ? <p className="failure-copy">{run.error.message}</p> : <SafeMarkdown content={run.summary ?? '该次执行未产生可读最终结论。'} />}
    {artifacts.length > 0 && <ArtifactReferenceList artifacts={artifacts} compact />}</div>
  </section>;
}
