import { CircleCheck, CircleDashed, CircleX, LoaderCircle, WifiOff } from 'lucide-react';
import type { RunStatus } from '@/domain/runtime/types';

const statusPresentation: Record<RunStatus, { label: string; icon: typeof CircleCheck; className: string }> = {
  QUEUED: { label: '等待创建', icon: CircleDashed, className: 'status-queued' },
  CONNECTING: { label: '连接运行输出', icon: LoaderCircle, className: 'status-running' },
  RUNNING: { label: '运行中', icon: LoaderCircle, className: 'status-running' },
  WAITING_USER_INPUT: { label: '等待用户操作', icon: CircleDashed, className: 'status-queued' },
  COMPLETED: { label: '已完成', icon: CircleCheck, className: 'status-completed' },
  FAILED: { label: '运行失败', icon: CircleX, className: 'status-failed' },
  DISCONNECTED: { label: '连接中断', icon: WifiOff, className: 'status-disconnected' },
};

export function RunStatusBadge({ status }: { status: RunStatus }) {
  const presentation = statusPresentation[status];
  const Icon = presentation.icon;
  return <span className={`run-status ${presentation.className}`}><Icon className={status === 'RUNNING' || status === 'CONNECTING' ? 'spin' : ''} size={14} />{presentation.label}</span>;
}
