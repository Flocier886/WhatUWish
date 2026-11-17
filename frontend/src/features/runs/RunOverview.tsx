import { ArrowUpRight, Clock3 } from 'lucide-react';
import type { RunSummary } from '@/domain/runtime/types';
import { RunStatusBadge } from './RunStatusBadge';

export function RunOverview({ run, eventCount }: { run?: RunSummary; eventCount: number }) {
  if (!run) return <section className="run-overview run-overview-empty"><span className="welcome-kicker">WhatUWish</span><h1>把目标交给能持续执行的 Agent。</h1><p>选择一个 Agent，描述要完成的工作。执行中的思考、工具使用和结果会在这里按顺序出现。</p><span className="welcome-arrow"><ArrowUpRight size={16} /> 从下方开始</span></section>;
  return <section className="run-overview" aria-label="当前运行概览">
    <div className="message-avatar user-avatar">你</div><div className="run-overview-content"><div className="run-overview-top"><div><span className="eyebrow">{run.isHistorical ? '历史回放' : '你的任务'}</span><h2>{run.task}</h2></div><RunStatusBadge status={run.status} /></div>
    <dl className="run-facts"><div><dt><Clock3 size={13} />{run.startedAt ? new Date(run.startedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '等待开始'}</dt><dd>{eventCount} 个活动步骤</dd></div><div><dt>Run</dt><dd className="mono">{run.runId}</dd></div></dl></div>
  </section>;
}
