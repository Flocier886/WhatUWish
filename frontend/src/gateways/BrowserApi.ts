import { apiUrl } from './api-base';

type Result<T> = { code?: string; info?: string; data?: T };

/** 浏览器接口统一解包本站 Response；非成功码交给控件展示。 */
async function request<T>(path: string, method = 'GET'): Promise<T> {
  const response = await fetch(apiUrl(path), { method, headers: { Accept: 'application/json' } });
  const body = await response.json().catch(() => undefined) as Result<T> | undefined;
  if (!response.ok || body?.code !== '0000') throw new Error(body?.info || `浏览器请求失败（HTTP ${response.status}）。`);
  return body.data as T;
}

export type BrowserRelayStatus = { connected: boolean; tabUrl?: string; tabTitle?: string };
export type BrowserPairing = { token: string; code: string; relayUrl: string; expiresAt: number };
export type AgentBrowserStatus = { exists: boolean; agentSessionId?: string; lastUsedAt?: string; reconstructed: boolean };
export type AgentBrowserLiveView = { browserLiveViewUrl: string; agentSessionId: string; reconstructed: boolean };

/** 本机扩展配对、连接状态和断开入口。 */
export const browserRelayApi = {
  status: () => request<BrowserRelayStatus>('/api/v1/browser/status'),
  pairing: () => request<BrowserPairing>('/api/v1/browser/pairing', 'POST'),
  disconnect: () => request<void>('/api/v1/browser/disconnect', 'POST'),
};

/** 远端浏览器状态、实时画面和重置入口。 */
export const agentBrowserApi = {
  status: () => request<AgentBrowserStatus>('/api/v1/agent-browser/status'),
  ensure: () => request<AgentBrowserLiveView>('/api/v1/agent-browser/ensure', 'POST'),
  reset: () => request<void>('/api/v1/agent-browser/session', 'DELETE'),
};
