import { Bot, RefreshCw } from 'lucide-react';
import type { AgentSummary } from '@/domain/runtime/types';
import { EmptyState } from '@/components/shared/EmptyState';
import { AgentOption } from './AgentOption';

export function AgentCatalog({ agents, selectedAgentId, onSelect, loading, onRefresh }: { agents: AgentSummary[]; selectedAgentId?: string; onSelect(agentId: string): void; loading: boolean; onRefresh(): void }) {
  return <section className="agent-catalog" aria-label="可用 Agent">
    <div className="panel-heading"><div><span className="eyebrow"><Bot size={13} /> AGENTS</span><h2>运行目标</h2></div><button className="icon-button" type="button" title="刷新 Agent 列表" aria-label="刷新 Agent 列表" onClick={onRefresh}><RefreshCw size={16} /></button></div>
    {loading ? <p className="muted-copy">正在加载 Agent...</p> : agents.length === 0 ? <EmptyState title="没有可用 Agent" description="当前数据源未返回可执行的 Agent。" /> : <div className="agent-list">{agents.map((agent) => <AgentOption key={agent.agentId} agent={agent} selected={agent.agentId === selectedAgentId} onSelect={onSelect} />)}</div>}
  </section>;
}
