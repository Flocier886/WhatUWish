import { Bot, CircleOff, Sparkles } from 'lucide-react';
import type { AgentSummary } from '@/domain/runtime/types';

export function AgentOption({ agent, selected, onSelect }: { agent: AgentSummary; selected: boolean; onSelect(agentId: string): void }) {
  return <button type="button" className={`agent-option ${selected ? 'agent-option-selected' : ''}`} onClick={() => onSelect(agent.agentId)} aria-pressed={selected} disabled={agent.availability === 'UNAVAILABLE'}>
    <span className="agent-option-icon">{agent.availability === 'UNAVAILABLE' ? <CircleOff size={16} /> : agent.strategy === 'REACT' ? <Sparkles size={16} /> : <Bot size={16} />}</span>
    <span className="agent-option-copy"><strong>{agent.name}</strong><small>{agent.strategy === 'REACT' ? 'ReAct' : '兼容模式'} · {agent.availability === 'AVAILABLE' ? '可用' : '不可用'}</small></span>
  </button>;
}
