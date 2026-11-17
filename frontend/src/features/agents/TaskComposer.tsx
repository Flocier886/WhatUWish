import { Bot, Check, ChevronDown, FileText, FolderOpen, LoaderCircle, Paperclip, RefreshCw, Send, SlidersHorizontal, Trash2, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import type { AgentSummary } from '@/domain/runtime/types';
import { conversationFileApi, type ConversationFile } from '@/gateways/ConversationFileApi';
import { useAttachmentUploads } from './useAttachmentUploads';
import { ConversationFilePreviewDialog } from './ConversationFilePreviewDialog';
import { AgentBrowserControl, BrowserRelayControl } from './BrowserControls';

function agentStrategyLabel(agent?: AgentSummary) {
  if (!agent) return '请选择一个 Agent';
  return agent.strategy === 'REACT' ? 'ReAct · 适合多步推理和工具执行' : '兼容模式 · 保留旧版执行流程';
}

export function TaskComposer({ agents, selectedAgentId, sessionId = '', onSelect, onCreateSession, submitting, onSubmit, suggestedTask, onSuggestedTaskUsed }: { agents: AgentSummary[]; selectedAgentId?: string; sessionId?: string; onSelect(agentId: string): void; onCreateSession?(): Promise<void>; submitting: boolean; onSubmit(task: string, maxStep: number, fileIds?: string[]): Promise<boolean | void>; suggestedTask?: string; onSuggestedTaskUsed?(): void }) {
  const [task, setTask] = useState('');
  const [maxStep, setMaxStep] = useState(10);
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [agentMenuOpen, setAgentMenuOpen] = useState(false);
  const agentPickerRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const filesPanelRef = useRef<HTMLDivElement>(null);
  const filesButtonRef = useRef<HTMLButtonElement>(null);
  const [filesOpen, setFilesOpen] = useState(false);
  const [existingFiles, setExistingFiles] = useState<ConversationFile[]>([]);
  const [selectedExisting, setSelectedExisting] = useState<string[]>([]);
  const [fileError, setFileError] = useState<string>();
  const [previewFile, setPreviewFile] = useState<ConversationFile>();
  const uploads = useAttachmentUploads(sessionId);
  const hasFileAccess = Boolean(sessionId);
  const queued = uploads.order.map((id) => uploads.queue[id]).filter((item) => Boolean(item));
  const blockedByUpload = queued.some((item) => item.status === 'pending' || item.status === 'uploading' || item.status === 'error');
  // 新上传和会话已有文件分开管理；发送时只按用户选择顺序合并去重后的 READY fileId。
  const queuedFileIds = queued.filter((item) => item.status === 'success' && item.uploadedFile?.status === 'READY')
    .map((item) => item.uploadedFile!.fileId);
  const selectedFileIds = [...queuedFileIds, ...selectedExisting.filter((id) => !queuedFileIds.includes(id))];
  const selectableAgents = useMemo(() => agents.filter((agent) => agent.availability === 'AVAILABLE'), [agents]);
  const selectedAgent = selectableAgents.find((agent) => agent.agentId === selectedAgentId);

  const refreshFiles = useCallback(async () => {
    if (!hasFileAccess) return;
    try { setExistingFiles(await conversationFileApi.list(sessionId)); setFileError(undefined); }
    catch (cause) { setFileError(cause instanceof Error ? cause.message : '无法加载会话文件。'); }
  }, [hasFileAccess, sessionId]);

  useEffect(() => {
    setExistingFiles([]);
    setSelectedExisting([]);
    setFilesOpen(false);
    setPreviewFile(undefined);
    setNotice(undefined);
    setError(undefined);
  }, [sessionId]);

  useEffect(() => { if (filesOpen) void refreshFiles(); }, [filesOpen, refreshFiles]);

  useEffect(() => {
    if (!filesOpen) return;
    const close = (event: MouseEvent) => {
      if (!filesPanelRef.current?.contains(event.target as Node) && !filesButtonRef.current?.contains(event.target as Node)) setFilesOpen(false);
    };
    const onEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setFilesOpen(false); };
    window.addEventListener('mousedown', close);
    window.addEventListener('keydown', onEscape);
    return () => { window.removeEventListener('mousedown', close); window.removeEventListener('keydown', onEscape); };
  }, [filesOpen]);

  useEffect(() => {
    if (!suggestedTask) return;
    setTask(suggestedTask);
    setError(undefined);
    setNotice('已把建议提示填入输入框，可以直接编辑后发送。');
    onSuggestedTaskUsed?.();
  }, [onSuggestedTaskUsed, suggestedTask]);

  useEffect(() => {
    if (!agentMenuOpen) return;
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (!agentPickerRef.current?.contains(event.target as Node)) setAgentMenuOpen(false);
    };
    window.addEventListener('mousedown', closeOnOutsideClick);
    return () => window.removeEventListener('mousedown', closeOnOutsideClick);
  }, [agentMenuOpen]);

  /** 上传仍在进行或已失败时阻止发送；请求失败保留消息与附件，方便用户重试。 */
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (submitting) return;
    if (!selectedAgentId) { setError('请选择一个可用 Agent 后再发送消息。'); return; }
    if (!task.trim()) { setError('请输入消息。'); return; }
    setError(undefined);
    setNotice(undefined);
    if (blockedByUpload) { setError('请先完成、重试或移除待上传文件。'); return; }
    try {
      const sent = selectedFileIds.length ? await onSubmit(task, maxStep, selectedFileIds) : await onSubmit(task, maxStep);
      if (sent === false) { setError('发送失败，消息和附件已保留，请重试。'); return; }
      setTask('');
      setSelectedExisting([]);
      uploads.clearSucceeded();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '发送失败，消息和附件已保留。');
    }
  }

  function chooseFiles(event: ChangeEvent<HTMLInputElement>) {
    uploads.add(Array.from(event.target.files ?? []));
    event.target.value = '';
  }

  async function removeSessionFile(file: ConversationFile) {
    try {
      await conversationFileApi.remove(sessionId, file.fileId);
      setExistingFiles((current) => current.map((item) => item.fileId === file.fileId ? { ...item, status: 'REMOVED', downloadSupported: false, preview: null } : item));
      setSelectedExisting((current) => current.filter((id) => id !== file.fileId));
    } catch (cause) { setFileError(cause instanceof Error ? cause.message : '移除文件失败。'); }
  }

  function selectAgent(agentId: string) {
    onSelect(agentId);
    setAgentMenuOpen(false);
    setError(undefined);
  }

  return <form className="task-composer" onSubmit={submit}>
    <div className="composer-input-shell">
      <label className="sr-only" htmlFor="task-input">消息</label>
      <textarea id="task-input" value={task} onChange={(event) => { setTask(event.target.value); setNotice(undefined); }} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }} maxLength={2000} rows={2} placeholder="输入问题或任务，Shift + Enter 换行" />
      {queued.length > 0 && <div className="composer-upload-list" aria-label="本轮附件">
        {queued.map((item) => <div className="composer-upload-item" key={item.id}>
          <FileText size={15} /><span className="composer-file-name">{item.file.name}</span>
          <small>{item.status === 'uploading' ? `${item.progress}%` : item.status === 'pending' ? '等待上传' : item.status === 'success' ? '已就绪' : item.error}</small>
          {item.status === 'error' && <button type="button" aria-label={`重试 ${item.file.name}`} title="重试上传" onClick={() => uploads.retry(item.id)}><RefreshCw size={15} /></button>}
          <button type="button" aria-label={`移除本轮附件 ${item.file.name}`} title="移除本轮附件" onClick={() => uploads.remove(item.id)}><X size={15} /></button>
        </div>)}
      </div>}
      {filesOpen && <div ref={filesPanelRef} className="composer-files-panel" role="region" aria-label="会话文件">
        {!hasFileAccess ? <div className="composer-files-empty">此历史会话没有文件访问凭据。{onCreateSession && <button type="button" onClick={() => { void onCreateSession(); }}>新建对话</button>}</div> : <>
          <div className="composer-files-heading"><strong>会话文件</strong><button type="button" onClick={() => fileInputRef.current?.click()}><Paperclip size={14} />上传</button></div>
          {fileError && <p role="alert" className="field-error">{fileError}</p>}
          {existingFiles.filter((file) => !queuedFileIds.includes(file.fileId)).length === 0 && <p className="composer-files-empty">暂无可复用文件</p>}
          {existingFiles.filter((file) => !queuedFileIds.includes(file.fileId)).map((file) => <div className="composer-existing-file" key={file.fileId}>
            <label><input type="checkbox" checked={selectedExisting.includes(file.fileId)} disabled={file.status !== 'READY'} onChange={(event) => setSelectedExisting((current) => event.target.checked ? [...current, file.fileId] : current.filter((id) => id !== file.fileId))} />
              <span className="composer-file-name">{file.name}</span><small>{file.status === 'READY' ? `${file.size.toLocaleString()} B` : file.status === 'REMOVED' ? '已移除' : '不可用'}</small></label>
            {file.status === 'READY' && <button type="button" title="查看文件" aria-label={`查看 ${file.name}`} onClick={() => setPreviewFile(file)}><FileText size={15} /></button>}
            {file.status === 'READY' && <button type="button" title="移除会话文件" aria-label={`移除会话文件 ${file.name}`} onClick={() => { void removeSessionFile(file); }}><Trash2 size={15} /></button>}
          </div>)}
        </>}
      </div>}
      <div className="composer-toolbar">
        <input ref={fileInputRef} type="file" multiple hidden onChange={chooseFiles} aria-label="选择附件" />
        <button className="composer-tool-chip composer-file-action" type="button" aria-label="附件" title="上传附件" onClick={() => { if (!hasFileAccess) { setNotice('当前会话没有上传凭据，请先新建对话。'); return; } fileInputRef.current?.click(); }}><Paperclip size={15} /><span>附件</span>{queuedFileIds.length > 0 && <small>{queuedFileIds.length}</small>}</button>
        <button ref={filesButtonRef} className="composer-tool-chip composer-file-action" type="button" aria-expanded={filesOpen} aria-label="会话文件" title="选择已有会话文件" onClick={() => setFilesOpen((open) => !open)}><FolderOpen size={15} /><span>会话文件</span>{selectedExisting.length > 0 && <small>{selectedExisting.length}</small>}</button>
        <BrowserRelayControl />
        <AgentBrowserControl />

        <div className="agent-picker" ref={agentPickerRef}>
          <button className="agent-picker-trigger" type="button" aria-haspopup="listbox" aria-expanded={agentMenuOpen} onClick={() => setAgentMenuOpen((open) => !open)} disabled={selectableAgents.length === 0}>
            <Bot size={16} />
            <span>{selectedAgent ? `${selectedAgent.name} · ${selectedAgent.strategy === 'REACT' ? 'ReAct' : '兼容模式'}` : '选择可用 Agent'}</span>
            <ChevronDown size={15} />
          </button>
          {agentMenuOpen && <div className="agent-picker-menu" role="listbox" aria-label="选择可用 Agent">
            <div className="agent-picker-menu-title">选择可用 Agent</div>
            {selectableAgents.length === 0 && <div className="agent-picker-empty">当前没有可用 Agent</div>}
            {selectableAgents.map((agent) => {
              const selected = agent.agentId === selectedAgentId;
              return <button className={`agent-picker-option ${selected ? 'agent-picker-option-selected' : ''}`} type="button" role="option" aria-selected={selected} key={agent.agentId} onClick={() => selectAgent(agent.agentId)}>
                <span className="agent-option-mark"><Bot size={17} /></span>
                <span className="agent-option-copy"><strong>{agent.name}</strong><small>{agentStrategyLabel(agent)}</small></span>
                {selected && <Check className="agent-option-check" size={18} />}
              </button>;
            })}
          </div>}
        </div>

        <label className="step-control"><SlidersHorizontal size={14} /><span>步数</span><input aria-label="最大步数" type="number" value={maxStep} min={1} max={20} onChange={(event) => setMaxStep(Number(event.target.value))} onBlur={() => setMaxStep((value) => Number.isFinite(value) ? Math.max(1, Math.min(20, value)) : 10)} /></label>
        <span className="composer-spacer" />
        <button className="command-button" type="submit" disabled={submitting || blockedByUpload || selectableAgents.length === 0} aria-label={submitting ? '正在发送' : '发送消息'}>{submitting ? <LoaderCircle className="spin" size={18} /> : <Send size={18} />}<span>{submitting ? '发送中' : '发送'}</span></button>
      </div>
    </div>
    {(error || notice) && <p className="composer-hint">{error ? <span className="field-error" role="alert">{error}</span> : notice}</p>}
    <ConversationFilePreviewDialog file={previewFile} onClose={() => setPreviewFile(undefined)} />
  </form>;
}
