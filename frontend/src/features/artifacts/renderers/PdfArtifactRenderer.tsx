import { useEffect, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, Download, RotateCw, ZoomIn, ZoomOut } from 'lucide-react';
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';
import 'pdfjs-dist/web/pdf_viewer.css';
import type { ArtifactRendererProps } from './MarkdownArtifactRenderer';
import styles from './PdfArtifactRenderer.module.css';

/** master 的 PDF.js 页面与文字层内核；本站每次只渲染当前页。 */
export function PdfArtifactRenderer({ preview, downloadHref }: ArtifactRendererProps) {
  const objectUrl = preview.kind === 'binary' ? preview.objectUrl : undefined;
  const [document, setDocument] = useState<PDFDocumentProxy>();
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [pageNumber, setPageNumber] = useState(1);
  const [zoom, setZoom] = useState(1);
  const [width, setWidth] = useState(760);
  const [attempt, setAttempt] = useState(0);
  const hostRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const textRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const root = hostRef.current;
    if (!root) return;
    const observer = new ResizeObserver(() => setWidth(root.clientWidth));
    observer.observe(root);
    setWidth(root.clientWidth);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!objectUrl) return;
    let cancelled = false;
    let loaded: PDFDocumentProxy | undefined;
    setStatus('loading');
    setDocument(undefined);
    void (async () => {
      try {
        const [pdfjs, worker, response] = await Promise.all([
          import('pdfjs-dist'), import('pdfjs-dist/build/pdf.worker.min.mjs?url'), fetch(objectUrl),
        ]);
        if (!response.ok) throw new Error('文件内容不可读取');
        pdfjs.GlobalWorkerOptions.workerSrc = worker.default;
        loaded = await pdfjs.getDocument({ data: new Uint8Array(await response.arrayBuffer()) }).promise;
        if (cancelled) { await loaded.cleanup(); return; }
        setDocument(loaded);
      } catch {
        if (!cancelled) setStatus('error');
      }
    })();
    return () => { cancelled = true; if (loaded) void loaded.cleanup(); };
  }, [objectUrl, attempt]);

  useEffect(() => {
    if (!document) return;
    const canvas = canvasRef.current;
    const textRoot = textRef.current;
    if (!canvas || !textRoot) return;
    let cancelled = false;
    let renderTask: RenderTask | undefined;
    setStatus('loading');
    void (async () => {
      try {
        const pdfjs = await import('pdfjs-dist');
        const page = await document.getPage(pageNumber);
        if (cancelled) return;
        const natural = page.getViewport({ scale: 1 });
        const scale = Math.min(1.25, Math.max(0.1, (width - 24) / natural.width)) * zoom;
        const viewport = page.getViewport({ scale });
        const context = canvas.getContext('2d');
        if (!context) throw new Error('Canvas 不可用');
        canvas.width = Math.ceil(viewport.width);
        canvas.height = Math.ceil(viewport.height);
        canvas.style.width = `${viewport.width}px`;
        canvas.style.height = `${viewport.height}px`;
        textRoot.replaceChildren();
        textRoot.style.width = `${viewport.width}px`;
        textRoot.style.height = `${viewport.height}px`;
        textRoot.style.setProperty('--scale-factor', String(viewport.scale));
        renderTask = page.render({ canvas, canvasContext: context, viewport });
        await renderTask.promise;
        if (cancelled) return;
        try {
          const layer = new pdfjs.TextLayer({ textContentSource: await page.getTextContent(), container: textRoot, viewport });
          await layer.render();
        } catch { /* 文本层失败不影响页面图像。 */ }
        if (!cancelled) setStatus('ready');
      } catch (error) {
        if (!cancelled && !(error instanceof Error && error.name === 'RenderingCancelledException')) setStatus('error');
      }
    })();
    return () => { cancelled = true; renderTask?.cancel(); textRoot.replaceChildren(); };
  }, [document, pageNumber, width, zoom]);

  if (!objectUrl) return null;
  return <div className={styles.reader} ref={hostRef}>
    <div className={styles.toolbar}>
      <button type="button" title="上一页" aria-label="上一页" disabled={pageNumber <= 1 || !document} onClick={() => setPageNumber(pageNumber - 1)}><ChevronLeft size={17} /></button>
      <label className={styles.pageInput}><input aria-label="页码" type="number" min={1} max={document?.numPages ?? 1} value={pageNumber} onChange={(event) => setPageNumber(Math.min(document?.numPages ?? 1, Math.max(1, Number(event.target.value) || 1)))} /> / {document?.numPages ?? '-'}</label>
      <button type="button" title="下一页" aria-label="下一页" disabled={!document || pageNumber >= document.numPages} onClick={() => setPageNumber(pageNumber + 1)}><ChevronRight size={17} /></button>
      <span className={styles.separator} />
      <button type="button" title="缩小" aria-label="缩小" disabled={zoom <= 0.6} onClick={() => setZoom(Math.max(0.6, +(zoom - 0.15).toFixed(2)))}><ZoomOut size={17} /></button>
      <span className={styles.zoom}>{Math.round(zoom * 100)}%</span>
      <button type="button" title="放大" aria-label="放大" disabled={zoom >= 2.4} onClick={() => setZoom(Math.min(2.4, +(zoom + 0.15).toFixed(2)))}><ZoomIn size={17} /></button>
    </div>
    {status === 'error' && <div className={styles.state} role="alert"><p>PDF 文件无法预览</p><div>
      <button type="button" onClick={() => setAttempt((value) => value + 1)}><RotateCw size={15} />重试</button>
      {downloadHref && <a href={downloadHref}><Download size={15} />下载原件</a>}
    </div></div>}
    {status === 'loading' && <p className={styles.loading} role="status">正在渲染 PDF 页面</p>}
    <div className={styles.pageScroller} hidden={status === 'error'}><div className={styles.page} hidden={!document}>
      <canvas ref={canvasRef} /><div ref={textRef} className={`textLayer ${styles.textLayer}`} />
    </div></div>
  </div>;
}
