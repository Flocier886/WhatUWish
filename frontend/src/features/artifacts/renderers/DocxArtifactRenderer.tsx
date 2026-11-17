import { useEffect, useRef, useState } from 'react';
import { Download, RotateCw } from 'lucide-react';
import type { ArtifactRendererProps } from './MarkdownArtifactRenderer';
import styles from './DocxArtifactRenderer.module.css';

/** 搬用 master 的 docx-preview 参数；文件字节由本站授权网关提供。 */
export function DocxArtifactRenderer({ preview, downloadHref }: ArtifactRendererProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [attempt, setAttempt] = useState(0);
  const objectUrl = preview.kind === 'binary' ? preview.objectUrl : undefined;

  useEffect(() => {
    const root = rootRef.current;
    if (!root || !objectUrl) return;
    let cancelled = false;
    setState('loading');
    root.replaceChildren();
    void (async () => {
      try {
        const [module, response] = await Promise.all([import('docx-preview'), fetch(objectUrl)]);
        if (!response.ok) throw new Error('文件内容不可读取');
        const buffer = await response.arrayBuffer();
        if (cancelled) return;
        await module.renderAsync(buffer, root, undefined, {
          className: 'docx-preview', inWrapper: true, breakPages: true,
          renderHeaders: true, renderFooters: true, renderFootnotes: true,
          renderEndnotes: true, ignoreWidth: false, ignoreHeight: false, useBase64URL: true,
        });
        if (!cancelled) setState('ready');
      } catch {
        if (!cancelled) setState('error');
      }
    })();
    return () => { cancelled = true; root.replaceChildren(); };
  }, [objectUrl, attempt]);

  useEffect(() => {
    if (state !== 'ready') return;
    const root = rootRef.current;
    const container = root?.parentElement;
    const page = root?.querySelector<HTMLElement>('.docx-preview');
    if (!root || !container || !page) return;
    const pageWidth = page.getBoundingClientRect().width;
    root.style.width = `${pageWidth}px`;
    root.style.marginInline = 'auto';
    const fit = () => {
      const available = container.clientWidth;
      root.style.zoom = String(Math.min(1, Math.max(0.25, (available - 4) / pageWidth)));
    };
    const observer = new ResizeObserver(fit);
    observer.observe(container);
    fit();
    return () => { observer.disconnect(); root.style.zoom = ''; root.style.width = ''; root.style.marginInline = ''; };
  }, [state]);

  if (!objectUrl) return null;
  return <div className={styles.reader}>
    {state === 'loading' && <p className={styles.message} role="status">正在排版 Word 文档</p>}
    {state === 'error' && <div className={styles.message} role="alert">
      <p>Word 文件无法预览</p>
      <div className={styles.actions}>
        <button type="button" onClick={() => setAttempt((value) => value + 1)}><RotateCw size={15} />重试</button>
        {downloadHref && <a href={downloadHref}><Download size={15} />下载原件</a>}
      </div>
    </div>}
    <div ref={rootRef} className={styles.document} style={{ visibility: state === 'ready' ? 'visible' : 'hidden' }} />
  </div>;
}
