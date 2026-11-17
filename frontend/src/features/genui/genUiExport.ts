import { apiUrl } from '@/gateways/api-base';

export type GenUiExportFormat = 'pdf' | 'docx';

/** 导出前由后端核对 canvas 与 Run 的会话归属。 */
export async function exportGenUiFile({ sessionId, runId, canvasId, format, tree }: {
  sessionId: string; runId: string; canvasId: string; format: GenUiExportFormat; tree: unknown;
}): Promise<void> {
  const response = await fetch(apiUrl(`/api/v1/genui/sessions/${encodeURIComponent(sessionId)}/export/${format}`), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ runId, canvasId, tree, mode: 'document' }), cache: 'no-store',
  });
  if (!response.ok) {
    const body = await response.json().catch(() => undefined) as { info?: string } | undefined;
    throw new Error(body?.info || `导出失败（HTTP ${response.status}）。`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `genui-${new Date().toISOString().replace(/[:.]/g, '-')}.${format}`;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
