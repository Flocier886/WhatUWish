import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BrowserRelayControl } from './BrowserControls';

afterEach(() => vi.restoreAllMocks());

describe('BrowserRelayControl', () => {
  it('passes pairing token and relay URL to the extension bridge', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify({
      code: '0000', data: url.endsWith('/pairing')
        ? { token: 'token-1', code: '123456', relayUrl: 'ws://localhost/api/v1/browser/relay', expiresAt: 1 }
        : { connected: false },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));
    const send = vi.spyOn(window, 'postMessage');
    render(<BrowserRelayControl />);
    fireEvent.click(screen.getByRole('button', { name: '连接浏览器' }));
    await waitFor(() => expect(send).toHaveBeenCalledWith({
      source: 'cute', type: 'browser-pair', token: 'token-1',
      relayUrl: 'ws://localhost/api/v1/browser/relay',
    }, window.location.origin));
    expect(screen.getByText('码 123456')).toBeInTheDocument();
    vi.unstubAllGlobals();
  });

  it('shows pairing errors reported by the published extension bridge', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      code: '0000', data: { connected: false },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));
    render(<BrowserRelayControl />);
    act(() => window.dispatchEvent(new MessageEvent('message', {
      source: window,
      data: { source: 'cute-extension', type: 'browser-pair-result', ok: false, error: 'relay offline' },
    })));
    expect(await screen.findByTitle('relay offline')).toHaveTextContent('连接失败');
    vi.unstubAllGlobals();
  });
});
