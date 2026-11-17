import { History } from 'lucide-react';

export function SessionHeader({ runCount }: { runCount: number }) {
  return <div className="session-header"><span className="eyebrow"><History size={13} /> SESSION</span><h2>课程运营工作区</h2><p>{runCount} 个可回看的 Run</p></div>;
}
