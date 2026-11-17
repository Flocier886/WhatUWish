import { Check, LoaderCircle, Monitor } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { DesktopControlPayload } from '@/domain/runtime/types';

export function DesktopControlCard({ control, onOpen, onComplete }: {
  control: DesktopControlPayload;
  onOpen: () => void;
  onComplete: () => Promise<boolean>;
}) {
  const [busy, setBusy] = useState(false);
  const [expired, setExpired] = useState(() => control.holdUntil !== undefined && control.holdUntil <= Date.now() / 1000);
  useEffect(() => {
    setExpired(control.holdUntil !== undefined && control.holdUntil <= Date.now() / 1000);
    if (control.status !== 'PENDING' || control.holdUntil === undefined) return;
    const timer = window.setTimeout(() => setExpired(true), Math.max(0, control.holdUntil * 1000 - Date.now()));
    return () => window.clearTimeout(timer);
  }, [control.holdUntil, control.status]);
  const pending = control.status === 'PENDING';
  const complete = async () => {
    if (busy) return;
    setBusy(true);
    try { await onComplete(); } finally { setBusy(false); }
  };
  return <section className="desktop-control-card" aria-label="桌面接管">
    <div className="desktop-control-card-heading"><Monitor size={17} /><strong>桌面控制</strong>
      <span>{pending && !expired ? '待操作' : control.status === 'RESUMING' ? '继续执行中'
        : control.status === 'COMPLETED' || control.status === 'RESUME_PENDING' ? '已完成' : '已结束'}</span></div>
    <p>{control.reason}</p>
    <div className="desktop-control-card-actions">
      {pending && !expired && control.streamUrl && <button type="button" onClick={onOpen}><Monitor size={15} />在工作区查看</button>}
      <button type="button" disabled={!pending || expired || busy} onClick={() => void complete()}>
        {busy ? <LoaderCircle size={15} className="spin" /> : <Check size={15} />}
        {pending && !expired ? '我已完成' : '操作已结束'}
      </button>
    </div>
  </section>;
}
