import { ExternalLink, Monitor, PanelRightClose } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { DesktopControlPayload } from '@/domain/runtime/types';
import styles from './RuntimeWorkspace.module.css';

export function DesktopPreviewPanel({ control, onClose, onComplete }: {
  control: DesktopControlPayload;
  onClose: () => void;
  onComplete: (controlId: string) => Promise<boolean>;
}) {
  const [busy, setBusy] = useState(false);
  const [expired, setExpired] = useState(() => control.holdUntil !== undefined && control.holdUntil <= Date.now() / 1000);
  useEffect(() => {
    setExpired(control.holdUntil !== undefined && control.holdUntil <= Date.now() / 1000);
    if (control.status !== 'PENDING' || control.holdUntil === undefined) return;
    const timer = window.setTimeout(() => setExpired(true), Math.max(0, control.holdUntil * 1000 - Date.now()));
    return () => window.clearTimeout(timer);
  }, [control.holdUntil, control.status]);
  const complete = async () => {
    if (busy) return;
    setBusy(true);
    try { await onComplete(control.controlId); } finally { setBusy(false); }
  };
  const active = control.status === 'PENDING' && !expired && Boolean(control.streamUrl);
  return <section className={styles.desktopPreview} aria-label="E2B 桌面预览">
    <header><span><Monitor size={16} />桌面预览</span><div>
      {active && <a href={control.streamUrl} target="_blank" rel="noopener noreferrer" title="在新窗口打开桌面" aria-label="在新窗口打开桌面"><ExternalLink size={16} /></a>}
      <button type="button" onClick={onClose} title="关闭桌面预览" aria-label="关闭桌面预览"><PanelRightClose size={16} /></button>
    </div></header>
    {active ? <iframe title="E2B 桌面实时预览" src={control.streamUrl} allow="clipboard-read; clipboard-write" />
      : <p>桌面会话已结束，历史记录仍保留本次接管状态。</p>}
    {active && <footer><button type="button" disabled={busy} onClick={() => void complete()}><Monitor size={15} />{busy ? '正在继续' : '我已完成'}</button></footer>}
  </section>;
}
