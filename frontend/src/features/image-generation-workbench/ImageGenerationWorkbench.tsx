import * as Dialog from '@radix-ui/react-dialog';
import { ArrowUp, Brush, Download, Eraser, ImagePlus, LoaderCircle, Paperclip, RefreshCw, Settings2, Trash2, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import {
  HttpImageGenerationWorkbenchDataSource,
  type ImageGenerationWorkbenchDataSource,
  type WorkbenchGenerationResult,
  type WorkbenchHistoryItem,
  type WorkbenchImageArtifact,
} from './ImageGenerationWorkbenchDataSource';
import styles from './ImageGenerationWorkbench.module.css';

type Mode = 'images' | 'edits';
type EditorTool = 'brush' | 'eraser';
type Draft = { id: string; file: File; objectUrl: string; width: number; height: number; mask?: File };
type Batch = {
  id: string;
  executionId?: string;
  prompt: string;
  referenceName?: string;
  status: string;
  summary?: string;
  errorMessage?: string;
  requestedCount?: number;
  generatedCount?: number;
  artifacts: WorkbenchImageArtifact[];
  createdAt?: string;
};

const PAGE_SIZE = 12;
const supported = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/bmp']);
const defaultDataSource = new HttpImageGenerationWorkbenchDataSource();
const identifier = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;

function hasPaint(canvas: HTMLCanvasElement): boolean {
  const pixels = canvas.getContext('2d')?.getImageData(0, 0, canvas.width, canvas.height).data;
  if (!pixels) return false;
  for (let index = 3; index < pixels.length; index += 4) if (pixels[index] > 0) return true;
  return false;
}

function canvasBlob(canvas: HTMLCanvasElement): Blob {
  const bytes = atob(canvas.toDataURL('image/png').split(',', 2)[1] || '');
  const value = new Uint8Array(bytes.length);
  for (let index = 0; index < bytes.length; index += 1) value[index] = bytes.charCodeAt(index);
  return new Blob([value], { type: 'image/png' });
}

function emptyMask(draft: Draft): File {
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, draft.width);
  canvas.height = Math.max(1, draft.height);
  return new File([canvasBlob(canvas)], `${draft.file.name.replace(/\.[^.]+$/, '') || 'reference'}-mask.png`, { type: 'image/png' });
}

function historyBatch(item: WorkbenchHistoryItem): Batch {
  return {
    id: `history-${item.executionId}`, executionId: item.executionId, prompt: item.summary || '图片生成',
    status: item.status, summary: item.summary, errorMessage: item.errorMessage,
    requestedCount: item.requestedCount, generatedCount: item.generatedCount,
    artifacts: item.artifacts ?? [], createdAt: item.startedAt,
  };
}

function previewable(artifact: WorkbenchImageArtifact): boolean {
  return artifact.accessState === 'AVAILABLE' && artifact.previewSupported === true
    && artifact.preview?.kind === 'image' && artifact.preview.schemaVersion === 'v1';
}

function statusLabel(status: string): string {
  if (status === 'generating') return '生成中';
  if (status === 'completed') return '已完成';
  if (status === 'failed') return '失败';
  return status;
}

function ImageCard({ executionId, artifact, dataSource, onPreview }: {
  executionId: string;
  artifact: WorkbenchImageArtifact;
  dataSource: ImageGenerationWorkbenchDataSource;
  onPreview: (src: string, name: string) => void;
}) {
  const [url, setUrl] = useState<string>();
  const [failed, setFailed] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState(false);

  useEffect(() => {
    if (!previewable(artifact)) { setUrl(undefined); return; }
    const controller = new AbortController();
    let objectUrl: string | undefined;
    setFailed(false);
    void dataSource.loadPreview(executionId, artifact.artifactId, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [artifact, dataSource, executionId]);

  const download = async () => {
    if (!artifact.downloadSupported || downloading) return;
    setDownloading(true);
    setDownloadError(false);
    try {
      const objectUrl = URL.createObjectURL(await dataSource.download(executionId, artifact.artifactId));
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = artifact.name || 'image.png';
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
    } catch { setDownloadError(true); }
    finally { setDownloading(false); }
  };

  const name = artifact.name || '生成图片';
  return <article className={styles.imageCard}>
    <div className={styles.imagePreview}>
      {url && <button type="button" className={styles.previewButton} aria-label={`预览 ${name}`} onClick={() => onPreview(url, name)}><img src={url} alt={name} /></button>}
      {!url && previewable(artifact) && !failed && <LoaderCircle className={styles.spinner} size={22} aria-label="正在加载图片" />}
      {(!previewable(artifact) || failed) && <span>{artifact.accessState === 'NOT_FOUND' ? '图片已不可用' : '图片预览不可用'}</span>}
      {artifact.downloadSupported && <button type="button" className={styles.downloadButton} title="下载图片" aria-label={`下载 ${name}`} disabled={downloading} onClick={() => { void download(); }}><Download size={16} /></button>}
    </div>
    <div className={styles.imageName} title={name}>{name}</div>
    {downloadError && <small className={styles.error} role="alert">下载失败，请重试。</small>}
  </article>;
}

export function ImageGenerationWorkbench({ sessionId, dataSource = defaultDataSource }: {
  sessionId: string;
  dataSource?: ImageGenerationWorkbenchDataSource;
}) {
  const [mode, setMode] = useState<Mode>('images');
  const [prompt, setPrompt] = useState('');
  const [size, setSize] = useState('1024x1024');
  const [count, setCount] = useState(1);
  const [batchMode, setBatchMode] = useState(true);
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [local, setLocal] = useState<Batch[]>([]);
  const [history, setHistory] = useState<Batch[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [historyError, setHistoryError] = useState<string>();
  const [inputError, setInputError] = useState<string>();
  const [starting, setStarting] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [preview, setPreview] = useState<{ src: string; name: string }>();
  const [tool, setTool] = useState<EditorTool>('brush');
  const [brushSize, setBrushSize] = useState(36);
  const [drawing, setDrawing] = useState(false);
  const [editorReady, setEditorReady] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const draftsRef = useRef<Draft[]>([]);
  const lastPoint = useRef<{ x: number; y: number } | undefined>(undefined);
  const selected = drafts.find((draft) => draft.id === selectedId);

  useEffect(() => { draftsRef.current = drafts; }, [drafts]);
  useEffect(() => () => { draftsRef.current.forEach((draft) => URL.revokeObjectURL(draft.objectUrl)); }, []);

  const loadHistory = useCallback(async (nextPage: number, append: boolean) => {
    setLoadingHistory(true);
    setHistoryError(undefined);
    try {
      const result = await dataSource.history(sessionId, nextPage, PAGE_SIZE);
      const incoming = result.items.map(historyBatch);
      setHistory((current) => append
        ? [...current, ...incoming.filter((item) => !current.some((value) => value.executionId === item.executionId))]
        : incoming);
      setPage(result.pageNo);
      setHasMore(result.hasMore);
    } catch (error) {
      setHistoryError(error instanceof Error ? error.message : '无法加载图片历史。');
    } finally { setLoadingHistory(false); }
  }, [dataSource, sessionId]);

  useEffect(() => {
    setLocal([]);
    setHistory([]);
    void loadHistory(1, false);
  }, [loadHistory]);

  const batches = useMemo(() => {
    const localIds = new Set(local.map((item) => item.executionId).filter(Boolean));
    return [...local, ...history.filter((item) => !item.executionId || !localIds.has(item.executionId))];
  }, [history, local]);

  useEffect(() => {
    const target = canvas.current;
    if (!target || !selected || !editorReady) return;
    target.width = Math.max(1, selected.width);
    target.height = Math.max(1, selected.height);
    const context = target.getContext('2d');
    if (!context) return;
    context.clearRect(0, 0, target.width, target.height);
    if (!selected.mask) return;
    const url = URL.createObjectURL(selected.mask);
    const image = new Image();
    image.onload = () => { context.drawImage(image, 0, 0, target.width, target.height); URL.revokeObjectURL(url); };
    image.onerror = () => URL.revokeObjectURL(url);
    image.src = url;
  }, [editorReady, selected]);

  const addFiles = async (files: FileList | null) => {
    const incoming = Array.from(files ?? []);
    if (incoming.some((file) => !supported.has(file.type))) setInputError('只支持 PNG、JPEG、WebP、GIF 和 BMP 图片。');
    else setInputError(undefined);
    const loaded = await Promise.all(incoming.filter((file) => supported.has(file.type)).map(async (file) => {
      const objectUrl = URL.createObjectURL(file);
      const dimensions = await new Promise<{ width: number; height: number }>((resolve) => {
        const image = new Image();
        image.onload = () => resolve({ width: image.naturalWidth || 1, height: image.naturalHeight || 1 });
        image.onerror = () => resolve({ width: 1, height: 1 });
        image.src = objectUrl;
      });
      return { id: identifier(), file, objectUrl, ...dimensions };
    }));
    if (loaded.length === 0) return;
    setDrafts((current) => [...current, ...loaded]);
    setSelectedId((current) => current ?? loaded[0].id);
    setMode('edits');
  };

  const removeDraft = (id: string) => {
    const next = drafts.filter((draft) => draft.id !== id);
    const removed = drafts.find((draft) => draft.id === id);
    if (removed) URL.revokeObjectURL(removed.objectUrl);
    setDrafts(next);
    if (selectedId === id) setSelectedId(next[0]?.id);
    if (editing && selectedId === id) setEditing(false);
    if (next.length === 0) setMode('images');
  };

  const persistMask = (): File | undefined => {
    const target = canvas.current;
    if (!target || !selected) return undefined;
    if (!hasPaint(target)) {
      setDrafts((current) => current.map((draft) => draft.id === selected.id ? { ...draft, mask: undefined } : draft));
      return undefined;
    }
    const mask = new File([canvasBlob(target)], `${selected.file.name.replace(/\.[^.]+$/, '') || 'reference'}-mask.png`, { type: 'image/png' });
    setDrafts((current) => current.map((draft) => draft.id === selected.id ? { ...draft, mask } : draft));
    return mask;
  };

  const closeEditor = () => { persistMask(); setEditing(false); setDrawing(false); };
  const point = (event: PointerEvent<HTMLCanvasElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    return { x: (event.clientX - rect.left) * (event.currentTarget.width / rect.width), y: (event.clientY - rect.top) * (event.currentTarget.height / rect.height) };
  };
  const paint = (from: { x: number; y: number }, to: { x: number; y: number }) => {
    const context = canvas.current?.getContext('2d');
    if (!context) return;
    context.globalCompositeOperation = tool === 'eraser' ? 'destination-out' : 'source-over';
    context.strokeStyle = 'rgba(225, 50, 50, .56)';
    context.lineWidth = brushSize;
    context.lineCap = 'round';
    context.beginPath();
    context.moveTo(from.x, from.y);
    context.lineTo(to.x, to.y);
    context.stroke();
  };

  const submit = async () => {
    const value = prompt.trim();
    if (starting || !value || (mode === 'edits' && drafts.length === 0)) return;
    const id = identifier();
    setStarting(true);
    setInputError(undefined);
    try {
      const currentMask = editing ? persistMask() : undefined;
      const prepared = drafts.map((draft) => draft.id === selected?.id && currentMask ? { ...draft, mask: currentMask } : draft);
      // 与 master 的批处理相同：多图编辑逐张提交，保留每张图的遮罩和独立结果。
      if (mode === 'edits' && batchMode && prepared.length > 1) {
        const requests = prepared.map((draft) => ({ draft, id: identifier() }));
        setLocal((current) => [
          ...requests.map(({ draft, id }) => ({ id, prompt: value, referenceName: draft.file.name, status: 'generating', artifacts: [], createdAt: new Date().toISOString() })),
          ...current,
        ]);
        const succeeded = await Promise.all(requests.map(async ({ draft, id }) => {
          try {
            const result = await dataSource.generate({
              sessionId, prompt: value, mode: 'edits', size, count,
              outputFileName: draft.file.name.replace(/\.[^.]+$/, '') || draft.file.name,
              references: [draft.file], masks: draft.mask ? [draft.mask] : [],
            });
            setLocal((current) => current.map((item) => item.id === id
              ? { ...item, executionId: result.executionId, status: result.status, summary: result.summary, errorMessage: result.errorMessage, requestedCount: result.requestedCount, generatedCount: result.generatedCount, artifacts: result.artifacts ?? [] }
              : item));
            return result.status === 'completed';
          } catch (error) {
            setLocal((current) => current.map((item) => item.id === id
              ? { ...item, status: 'failed', errorMessage: error instanceof Error ? error.message : '图片生成请求失败。' }
              : item));
            return false;
          }
        }));
        if (succeeded.every(Boolean)) setPrompt('');
        void loadHistory(1, false);
        return;
      }
      setLocal((current) => [{ id, prompt: value, status: 'generating', artifacts: [], createdAt: new Date().toISOString() }, ...current]);
      const masks = mode === 'edits' && prepared.some((draft) => draft.mask)
        ? prepared.map((draft) => draft.mask ?? emptyMask(draft)) : [];
      const result: WorkbenchGenerationResult = await dataSource.generate({ sessionId, prompt: value, mode, size, count, references: mode === 'edits' ? prepared.map((draft) => draft.file) : [], masks });
      setLocal((current) => current.map((item) => item.id === id ? { ...item, executionId: result.executionId, status: result.status, summary: result.summary, errorMessage: result.errorMessage, requestedCount: result.requestedCount, generatedCount: result.generatedCount, artifacts: result.artifacts ?? [] } : item));
      if (result.status !== 'failed') setPrompt('');
      void loadHistory(1, false);
    } catch (error) {
      setLocal((current) => current.map((item) => item.id === id ? { ...item, status: 'failed', errorMessage: error instanceof Error ? error.message : '图片生成请求失败。' } : item));
    } finally { setStarting(false); }
  };

  const onPromptKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void submit(); }
  };

  return <section className={styles.workbench} aria-label="图片生成工作台">
    <div className={styles.content}>
      <header className={styles.header}>
        <h1>图片</h1>
        <button type="button" className={styles.iconButton} aria-label="生成设置" title="生成设置" onClick={() => setSettingsOpen(true)}><Settings2 size={18} /></button>
      </header>

      <div className={styles.composer}>
        <div className={styles.modeSwitch} role="group" aria-label="生成模式">
          <button type="button" aria-pressed={mode === 'images'} className={mode === 'images' ? styles.modeActive : ''} onClick={() => setMode('images')}>文生图</button>
          <button type="button" aria-pressed={mode === 'edits'} className={mode === 'edits' ? styles.modeActive : ''} onClick={() => setMode('edits')}>图生图 / 编辑</button>
        </div>
        <div className={styles.promptRow}>
          <button type="button" className={styles.attachButton} aria-label="添加参考图片" title="添加参考图片" onClick={() => fileInput.current?.click()}><Paperclip size={18} /></button>
          <input ref={fileInput} type="file" hidden accept="image/png,image/jpeg,image/webp,image/gif,image/bmp" multiple onChange={(event) => { void addFiles(event.target.files); event.currentTarget.value = ''; }} />
          <textarea aria-label="图片描述" value={prompt} onChange={(event) => setPrompt(event.target.value)} onKeyDown={onPromptKeyDown} placeholder={mode === 'edits' ? '描述如何修改这些图片' : '描述新图片'} rows={2} />
          <button type="button" className={styles.generateButton} aria-label="生成" title="生成" disabled={starting || !prompt.trim() || (mode === 'edits' && drafts.length === 0)} onClick={() => { void submit(); }}>{starting ? <LoaderCircle className={styles.spinner} size={18} /> : <ArrowUp size={18} />}</button>
        </div>
        {inputError && <p className={styles.error} role="alert">{inputError}</p>}
        {mode === 'edits' && drafts.length > 1 && <label className={styles.batchToggle}>
          <input type="checkbox" checked={batchMode} onChange={(event) => setBatchMode(event.target.checked)} />
          多图批处理
        </label>}
        {drafts.length > 0 && <div className={styles.references} aria-label="参考图片">
          {drafts.map((draft, index) => <div className={styles.reference} key={draft.id}>
            <img src={draft.objectUrl} alt={`参考图 ${index + 1}`} />
            {draft.mask && <span className={styles.maskBadge}>已涂抹</span>}
            <div className={styles.referenceActions}>
              <button type="button" aria-label={`编辑 ${draft.file.name}`} title="编辑遮罩" onClick={() => { setSelectedId(draft.id); setEditorReady(false); setEditing(true); }}><Brush size={15} /></button>
              <button type="button" aria-label={`移除 ${draft.file.name}`} title="移除参考图" onClick={() => removeDraft(draft.id)}><X size={15} /></button>
            </div>
          </div>)}
          <button type="button" className={styles.addReference} aria-label="继续添加参考图片" title="继续添加参考图片" onClick={() => fileInput.current?.click()}><ImagePlus size={19} /></button>
        </div>}
      </div>

      {(batches.length > 0 || loadingHistory || historyError) && <section className={styles.results} aria-label="我的图片">
        <div className={styles.resultsHeader}><h2>我的图片</h2><button type="button" className={styles.refreshButton} aria-label="刷新图片历史" title="刷新图片历史" disabled={loadingHistory} onClick={() => { void loadHistory(1, false); }}><RefreshCw size={16} /></button></div>
        {historyError && <p className={styles.error} role="alert">{historyError}</p>}
        {batches.map((item) => <section className={styles.batch} key={item.id}>
          <div className={styles.batchHeader}><p>{item.prompt}{item.referenceName && <small className={styles.referenceName}>{item.referenceName}</small>}</p><span data-status={item.status}>{item.status === 'generating' && <LoaderCircle className={styles.spinner} size={14} />}{statusLabel(item.status)}</span></div>
          {item.errorMessage && <p className={styles.error} role="alert">{item.errorMessage}</p>}
          {item.status === 'completed' && item.requestedCount != null && item.generatedCount != null && item.generatedCount < item.requestedCount &&
            <p className={styles.error} role="status">请求 {item.requestedCount} 张，实际生成 {item.generatedCount} 张。</p>}
          {item.summary && item.summary !== item.prompt && item.summary !== item.errorMessage && <p className={styles.summary}>{item.summary}</p>}
          <div className={styles.gallery}>{item.executionId && item.artifacts.map((artifact) => <ImageCard key={artifact.artifactId} executionId={item.executionId!} artifact={artifact} dataSource={dataSource} onPreview={(src, name) => setPreview({ src, name })} />)}</div>
        </section>)}
        {hasMore && <button type="button" className={styles.loadMore} disabled={loadingHistory} onClick={() => { void loadHistory(page + 1, true); }}>{loadingHistory ? '正在加载' : '加载更多'}</button>}
      </section>}
    </div>

    <Dialog.Root open={settingsOpen} onOpenChange={setSettingsOpen}>
      <Dialog.Portal><Dialog.Overlay className={styles.overlay} /><Dialog.Content className={styles.settings} aria-describedby={undefined}>
        <div className={styles.dialogHeader}><Dialog.Title>生成设置</Dialog.Title><Dialog.Close className={styles.iconButton} aria-label="关闭生成设置"><X size={18} /></Dialog.Close></div>
        <label>图片尺寸<input aria-label="图片尺寸" value={size} onChange={(event) => setSize(event.target.value)} /></label>
        <label>输出数量<input aria-label="输出数量" type="number" min={1} max={10} value={count} onChange={(event) => setCount(Math.max(1, Math.min(10, Number(event.target.value) || 1)))} /></label>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>

    <Dialog.Root open={editing && Boolean(selected)} onOpenChange={(open) => { if (!open) closeEditor(); }}>
      <Dialog.Portal><Dialog.Overlay className={styles.editorOverlay} /><Dialog.Content className={styles.editorDialog} aria-describedby={undefined}>
        <div className={styles.editorHeader}><Dialog.Title>{selected?.file.name ?? '编辑参考图片'}</Dialog.Title><button type="button" className={styles.iconButton} aria-label="关闭遮罩编辑" onClick={closeEditor}><X size={18} /></button></div>
        <div className={styles.editorCanvas}>
          {selected && <div className={styles.editorImage}><img src={selected.objectUrl} alt="编辑中的参考图片" onLoad={() => setEditorReady(true)} draggable={false} /><canvas ref={canvas}
            onPointerDown={(event) => { event.currentTarget.setPointerCapture(event.pointerId); const next = point(event); lastPoint.current = next; setDrawing(true); paint(next, next); }}
            onPointerMove={(event) => { if (!drawing || !lastPoint.current) return; const next = point(event); paint(lastPoint.current, next); lastPoint.current = next; }}
            onPointerUp={() => { setDrawing(false); lastPoint.current = undefined; persistMask(); }}
            onPointerCancel={() => { setDrawing(false); lastPoint.current = undefined; persistMask(); }} /></div>}
        </div>
        <div className={styles.editorToolbar}>
          <div className={styles.toolGroup}><button type="button" aria-label="绘制遮罩" aria-pressed={tool === 'brush'} onClick={() => setTool('brush')}><Brush size={17} /></button><button type="button" aria-label="擦除遮罩" aria-pressed={tool === 'eraser'} onClick={() => setTool('eraser')}><Eraser size={17} /></button></div>
          <input aria-label="遮罩笔刷大小" type="range" min={8} max={120} value={brushSize} onChange={(event) => setBrushSize(Number(event.target.value))} />
          <button type="button" className={styles.iconButton} aria-label="清空遮罩" title="清空遮罩" onClick={() => { const context = canvas.current?.getContext('2d'); if (canvas.current && context) context.clearRect(0, 0, canvas.current.width, canvas.current.height); if (selected) setDrafts((current) => current.map((draft) => draft.id === selected.id ? { ...draft, mask: undefined } : draft)); }}><Trash2 size={17} /></button>
          <button type="button" className={styles.doneButton} onClick={closeEditor}>完成</button>
        </div>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>

    <Dialog.Root open={Boolean(preview)} onOpenChange={(open) => { if (!open) setPreview(undefined); }}>
      <Dialog.Portal><Dialog.Overlay className={styles.previewOverlay} /><Dialog.Content className={styles.previewDialog} aria-describedby={undefined}>
        <Dialog.Title className={styles.visuallyHidden}>{preview?.name ?? '图片预览'}</Dialog.Title>
        <Dialog.Close className={styles.previewClose} aria-label="关闭图片预览"><X size={20} /></Dialog.Close>
        {preview && <img src={preview.src} alt={preview.name} />}
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  </section>;
}
