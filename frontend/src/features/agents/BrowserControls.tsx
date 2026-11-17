import { Cloud, Globe, RotateCcw } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { agentBrowserApi, browserRelayApi, type AgentBrowserStatus, type BrowserPairing, type BrowserRelayStatus } from '@/gateways/BrowserApi';

/** 本机浏览器控件：轮询连接状态，配对信息按扩展约定通过 window.postMessage 交付。 */
export function BrowserRelayControl() {
  const [status, setStatus] = useState<BrowserRelayStatus>({ connected: false });
  const [pairing, setPairing] = useState<BrowserPairing>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const refresh = useCallback(async () => {
    try {
      const current = await browserRelayApi.status();
      setStatus(current);
      if (current.connected) setPairing(undefined);
    } catch { setStatus({ connected: false }); }
  }, []);

  // 配对中提高轮询频率，让扩展连接后及时收起短码。
  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), pairing && !status.connected ? 1000 : 5000);
    return () => window.clearInterval(timer);
  }, [refresh, pairing, status.connected]);

  // 扩展把配对结果回传给页面；只有成功才刷新连接状态。
  useEffect(() => {
    const receive = (event: MessageEvent) => {
      if (event.source !== window || event.data?.source !== 'cute-extension' || event.data?.type !== 'browser-pair-result') return;
      if (event.data.ok) void refresh();
      else setError(event.data.error || '扩展配对失败');
    };
    window.addEventListener('message', receive);
    return () => window.removeEventListener('message', receive);
  }, [refresh]);

  async function toggle() {
    setBusy(true); setError('');
    try {
      if (status.connected) {
        await browserRelayApi.disconnect();
        setStatus({ connected: false }); setPairing(undefined);
      } else {
        const next = await browserRelayApi.pairing();
        setPairing(next);
        window.postMessage({ source: 'cute', type: 'browser-pair', token: next.token, relayUrl: next.relayUrl }, window.location.origin);
      }
    } catch (cause) { setError(cause instanceof Error ? cause.message : '浏览器操作失败'); }
    finally { setBusy(false); }
  }

  return <span className="browser-control">
    <button className="composer-tool-chip" type="button" disabled={busy} aria-label={status.connected ? '断开浏览器连接' : '连接浏览器'} title={status.connected ? (status.tabTitle || status.tabUrl || '断开浏览器') : '连接本机 Chrome/Edge'} onClick={() => void toggle()}>
      <Globe size={15} /><span className="browser-control-label">{status.connected ? '已连接' : '连接浏览器'}</span>
    </button>
    {pairing && !status.connected && <small title={pairing.relayUrl}>码 {pairing.code}</small>}
    {error && <small className="field-error" title={error}>连接失败</small>}
  </span>;
}

/** 远端浏览器控件：打开 Kernel 实时画面，或清除当前 owner 的会话。 */
export function AgentBrowserControl() {
  const [status, setStatus] = useState<AgentBrowserStatus>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const refresh = useCallback(async () => {
    try { setStatus(await agentBrowserApi.status()); } catch { setStatus(undefined); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);

  /** 先同步打开空白页，等异步 ensure 返回后跳转，避免被浏览器拦截弹窗。 */
  async function open() {
    const popup = window.open('about:blank', '_blank');
    if (!popup) { setError('请允许弹出窗口'); return; }
    popup.opener = null;
    setBusy(true); setError('');
    try {
      const live = await agentBrowserApi.ensure();
      if (!live.browserLiveViewUrl) throw new Error('实时画面地址不可用');
      popup.location.replace(live.browserLiveViewUrl);
      await refresh();
    } catch (cause) { popup.close(); setError(cause instanceof Error ? cause.message : '启动失败'); }
    finally { setBusy(false); }
  }

  async function reset() {
    setBusy(true); setError('');
    try { await agentBrowserApi.reset(); await refresh(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '重置失败'); }
    finally { setBusy(false); }
  }

  return <span className="browser-control">
    <button className="composer-tool-chip" type="button" disabled={busy} aria-label={status?.exists ? 'Agent 浏览器' : '启动 Agent 浏览器'} title={status?.lastUsedAt ? `最近使用：${new Date(status.lastUsedAt).toLocaleString('zh-CN')}` : '打开 Agent 浏览器'} onClick={() => void open()}>
      <Cloud size={15} /><span className="browser-control-label">{status?.exists ? 'Agent 浏览器' : '启动 Agent 浏览器'}</span>
    </button>
    {status?.exists && <button className="browser-reset" type="button" disabled={busy} title="重置 Agent 浏览器" aria-label="重置 Agent 浏览器" onClick={() => void reset()}><RotateCcw size={14} /></button>}
    {error && <small className="field-error" title={error}>不可用</small>}
  </span>;
}
