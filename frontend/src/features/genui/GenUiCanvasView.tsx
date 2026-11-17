import { Download, ExternalLink } from 'lucide-react';
import { useSyncExternalStore, useState } from 'react';
import type { GenUiCanvas } from './projectGenUiCanvases';
import GenUiInline from './GenUiInline';
import { applyUiPatches } from './applyUiPatch';
import { genUiLocalScopeKey, getLocalUiPatches, getGenUiLocalSnapshot, subscribeGenUiLocalTree } from './genUiLocalTreeStore';
import { exportGenUiFile, type GenUiExportFormat } from './genUiExport';

export function GenUiCanvasView({ canvas, onOpenWorkspace }: { canvas: GenUiCanvas; onOpenWorkspace?: () => void }) {
  useSyncExternalStore(subscribeGenUiLocalTree, getGenUiLocalSnapshot, getGenUiLocalSnapshot);
  const [exporting, setExporting] = useState<GenUiExportFormat>();
  const [error, setError] = useState('');
  const localPatches = getLocalUiPatches(genUiLocalScopeKey(canvas.sessionId, canvas.id));
  const tree = canvas.tree ? applyUiPatches(canvas.tree, localPatches) : undefined;
  const download = async (format: GenUiExportFormat) => {
    setExporting(format); setError('');
    try { await exportGenUiFile({ sessionId: canvas.sessionId, runId: canvas.runId,
      canvasId: canvas.id, format, tree }); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '导出失败。'); }
    finally { setExporting(undefined); }
  };
  return <section className="genui-surface" aria-label="交互画布">
    <div className="genui-toolbar">
      <strong>交互画布</strong>
      <div>
        {onOpenWorkspace && <button type="button" title="在工作台查看" aria-label="在工作台查看交互画布" onClick={onOpenWorkspace}><ExternalLink size={15} /></button>}
        {tree && <button type="button" title="导出 PDF 内容快照" aria-label="导出 PDF 内容快照" disabled={Boolean(exporting)} onClick={() => void download('pdf')}><Download size={15} /> PDF</button>}
        {tree && <button type="button" title="导出 DOCX 内容快照" aria-label="导出 DOCX 内容快照" disabled={Boolean(exporting)} onClick={() => void download('docx')}><Download size={15} /> DOCX</button>}
      </div>
    </div>
    {canvas.issue && <p role="status" className="genui-issue">{canvas.issue}</p>}
    {error && <p role="alert" className="genui-issue">{error}</p>}
    {tree && <GenUiInline tree={tree} patchCount={canvas.patchCount + localPatches.length}
      sessionId={canvas.sessionId} requestId={canvas.requestId} messageId={canvas.id} className="w-full" />
    }
  </section>;
}
