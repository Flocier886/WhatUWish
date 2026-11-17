import { Bot, Check, ChevronDown, CircleAlert, Copy, FileText, LoaderCircle, Sparkles } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import type { ArtifactReference, DesktopControlPayload, InputFilePayload, RunSummary, RuntimeConsoleState } from '@/domain/runtime/types';
import { DesktopControlCard } from './DesktopControlCard';
import { conversationFileApi, type ConversationFile } from '@/gateways/ConversationFileApi';
import { ConversationFilePreviewDialog } from '@/features/agents/ConversationFilePreviewDialog';
import type { RuntimeOperation } from '@/domain/runtime/runtime-operation';
import { SafeMarkdown } from '@/components/shared/SafeMarkdown';
import { ArtifactReferenceList } from '@/features/artifacts/ArtifactReferenceList';
import { usePresentationQueue } from './usePresentationQueue';
import { deriveRunPresentation } from './run-presentation';
import { RuntimeOperationList } from './RuntimeOperationList';
import { ReportLiveDraftView } from './ReportLiveDraft';
import type { RuntimeWorkspaceTarget } from '@/features/runtime-workspace/runtime-workspace-target';
import { projectGenUiCanvases } from '@/features/genui/projectGenUiCanvases';
import { GenUiCanvasView } from '@/features/genui/GenUiCanvasView';

const welcomeSuggestions = [
  '帮我分析这次运行输出是否合理',
  '根据当前需求生成实现计划',
  '检查最近会话中的失败原因',
  '整理一份可交付的测试报告',
  '解释 Agent 调用了哪些工具',
];

/** 输入图片只在后端 metadata 明确给出 image/v1 时直显，其他类型仍走 Reader/下载。 */
function InputFileImage({ sessionId, file }: { sessionId: string; file: InputFilePayload }) {
  const [imageUrl, setImageUrl] = useState<string>();
  useEffect(() => {
    if (file.status !== 'AVAILABLE') { setImageUrl(undefined); return; }
    let active = true;
    let url: string | undefined;
    void conversationFileApi.metadata(sessionId, file.fileId).then(async (metadata) => {
      if (metadata.preview?.kind !== 'image' || metadata.preview.schemaVersion !== 'v1') return;
      const blob = await conversationFileApi.content(sessionId, file.fileId, 'preview');
      if (!active) return;
      url = URL.createObjectURL(blob);
      setImageUrl(url);
    }).catch(() => { if (active) setImageUrl(undefined); });
    return () => { active = false; if (url) URL.revokeObjectURL(url); };
  }, [file.fileId, file.status, sessionId]);
  return imageUrl ? <img className="conversation-input-image" src={imageUrl} alt={file.name} /> : null;
}

function ProcessTrace({ events, hiddenCount }: { events: RuntimeConsoleState['eventsByRunId'][string]; hiddenCount: number }) {
  const [open, setOpen] = useState(false);
  const processEvents = events.filter((event) => event.eventType === 'LLM_RESPONSE').slice(0, hiddenCount);
  if (hiddenCount === 0 || processEvents.length === 0) return null;

  return <div className="process-trace">
    <button type="button" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
      <Sparkles size={13} /><span>已完成 {hiddenCount} 次分析与推理</span><ChevronDown size={14} />
    </button>
    {open && <div className="process-trace-content">
      {processEvents.map((event) => <div key={event.eventId}><SafeMarkdown content={String((event.payload as { text?: string }).text ?? '')} /></div>)}
    </div>}
  </div>;
}

function RunConversation({ run, state, operations, desktopControls = [], onCompleteDesktopControl, workspaceTarget, onWorkspaceSelect }: { run: RunSummary; state: RuntimeConsoleState; operations: RuntimeOperation[]; desktopControls?: DesktopControlPayload[]; onCompleteDesktopControl?: (controlId: string) => Promise<boolean>; workspaceTarget?: RuntimeWorkspaceTarget; onWorkspaceSelect: (target: RuntimeWorkspaceTarget) => void }) {
  const [previewFile, setPreviewFile] = useState<ConversationFile>();
  const [fileError, setFileError] = useState<string>();
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const events = useMemo(() => state.eventsByRunId[run.runId] ?? [], [run.runId, state.eventsByRunId]);
  const inputFiles = useMemo(() => events.filter((event) => event.eventType === 'INPUT_FILE')
    .map((event) => event.payload as InputFilePayload).sort((a, b) => a.order - b.order), [events]);
  const openInput = async (file: InputFilePayload) => {
    try {
      setFileError(undefined);
      setPreviewFile(await conversationFileApi.metadata(run.sessionId, file.fileId));
    } catch (cause) {
      setFileError(cause instanceof Error ? cause.message : '文件当前不可访问。');
    }
  };
  const artifacts = useMemo<ArtifactReference[]>(() => {
    const ids = new Set<string>();
    return events.flatMap((event) => {
      if (event.eventType !== 'ARTIFACT_CREATED') return [];
      const artifactId = (event.payload as ArtifactReference).artifactId;
      const artifact = state.artifactsById[artifactId];
      if (!artifact || ids.has(artifactId)) return [];
      ids.add(artifactId);
      return [{ ...artifact, runId: run.runId }];
    });
  }, [events, run.runId, state.artifactsById]);
  const view = useMemo(() => deriveRunPresentation(events, run), [events, run]);
  const genUiCanvases = useMemo(() => projectGenUiCanvases(events), [events]);
  const presentationEvents = view.finalEventId ? events.filter((event) => event.eventId === view.finalEventId) : [];
  const presentation = usePresentationQueue(presentationEvents, Boolean(run.summary || run.error || run.isHistorical));
  const visibleText = view.finalEventId ? presentation.textByEventId[view.finalEventId] ?? '' : view.finalText;
  const isActive = !run.isHistorical && (run.status === 'CONNECTING' || run.status === 'RUNNING');
  const isRevealing = view.finalEventId ? presentation.pendingEventIds.includes(view.finalEventId) : false;
  const reportDrafts = useMemo(() => Object.values(state.reportDraftsByToolInvocationId)
    .filter((draft) => draft.runId === run.runId)
    .sort((left, right) => left.toolInvocationId.localeCompare(right.toolInvocationId)), [run.runId, state.reportDraftsByToolInvocationId]);
  const copyAnswer = async () => {
    try {
      await navigator.clipboard.writeText(view.finalText);
      setCopyState('copied');
    } catch { setCopyState('failed'); }
  };

  return <article className="conversation-run">
    {run.task && <div className="conversation-message conversation-user"><span className="conversation-avatar user-avatar">你</span><div className="message-bubble"><p>{run.task}</p>
      {inputFiles.length > 0 && <div className="conversation-input-files" aria-label="本轮输入文件">{inputFiles.map((file) =>
        <button type="button" key={file.fileId} disabled={file.status !== 'AVAILABLE'}
          title={file.status === 'AVAILABLE' ? `查看 ${file.name}` : '文件已移除或不可用'}
          onClick={() => { void openInput(file); }}><FileText size={15} /><span>{file.name}</span><small>{file.size.toLocaleString()} B</small></button>)}</div>}
      {inputFiles.map((file) => <InputFileImage key={file.fileId} sessionId={run.sessionId} file={file} />)}
      {fileError && <small role="alert">{fileError}</small>}
    </div></div>}
    <div className="conversation-message conversation-agent"><span className="conversation-avatar agent-avatar"><Bot size={15} /></span><div className="agent-message-content">
      {isActive && <div className="agent-live-status" role="status" aria-live="polite">
        <span className="live-status-icon"><LoaderCircle size={14} /></span><span>{view.statusLabel}</span><span className="live-status-dots" aria-hidden="true"><i /><i /><i /></span>
      </div>}
      <RuntimeOperationList runId={run.runId} operations={operations} active={isActive} selected={workspaceTarget} onSelect={onWorkspaceSelect} />
      {desktopControls.map((control) => <DesktopControlCard key={control.controlId} control={control}
        onOpen={() => onWorkspaceSelect({ kind: 'desktop', runId: run.runId, controlId: control.controlId })}
        onComplete={() => onCompleteDesktopControl?.(control.controlId) ?? Promise.resolve(false)} />)}
      <ReportLiveDraftView drafts={reportDrafts} />
      <ArtifactReferenceList artifacts={artifacts} compact onSelectArtifact={(artifact) => onWorkspaceSelect({ kind: 'artifact', runId: run.runId, artifactId: artifact.artifactId })} />
      {genUiCanvases.map((canvas) => <GenUiCanvasView key={canvas.id} canvas={canvas}
        onOpenWorkspace={() => onWorkspaceSelect({ kind: 'genui', runId: run.runId, canvasId: canvas.id })} />)}
      <ProcessTrace events={events} hiddenCount={view.processCount} />
      {(visibleText || isActive) && <div className={`agent-answer ${isRevealing ? 'agent-answer-streaming' : ''} ${!visibleText ? 'agent-answer-pending' : ''}`} data-testid="final-answer" aria-live="polite">
        {visibleText ? <SafeMarkdown content={visibleText} /> : <span>正在生成回答</span>}
      </div>}
      {view.isFinal && view.finalText && <div className="answer-actions"><span className="answer-finalized"><Sparkles size={12} />回答已整理完成</span><button type="button" onClick={() => { void copyAnswer(); }} aria-label="复制回答原文" title="复制回答原文">{copyState === 'copied' ? <Check size={16} /> : <Copy size={16} />}</button>{copyState !== 'idle' && <span role="status">{copyState === 'copied' ? '已复制' : '复制失败，请检查浏览器权限'}</span>}</div>}
      {run.error && <div className="agent-error"><CircleAlert size={15} /><span>{run.error.message}</span></div>}
    </div></div>
    <ConversationFilePreviewDialog file={previewFile} onClose={() => setPreviewFile(undefined)} />
  </article>;
}

export function ConversationStream({ state, operationsByRunId = {}, desktopControls = [], onCompleteDesktopControl, workspaceTarget, onWorkspaceSelect = () => undefined, onSuggestionSelect }: { state: RuntimeConsoleState; operationsByRunId?: Record<string, RuntimeOperation[]>; desktopControls?: DesktopControlPayload[]; onCompleteDesktopControl?: (controlId: string) => Promise<boolean>; workspaceTarget?: RuntimeWorkspaceTarget; onWorkspaceSelect?: (target: RuntimeWorkspaceTarget) => void; onSuggestionSelect?(suggestion: string): void }) {
  const runs = useMemo(() => (state.runIdsBySession[state.activeSessionId] ?? []).map((runId) => state.runsById[runId]).filter((run): run is RunSummary => Boolean(run)).sort((left, right) => (left.startedAt ?? '').localeCompare(right.startedAt ?? '')), [state]);
  const loadIssue = [...state.issues].reverse().find((issue) => issue.sessionId === state.activeSessionId && issue.id.startsWith('conversation-'));
  if (runs.length === 0 && loadIssue) return <section className="conversation-welcome" role="alert">
    <CircleAlert size={24} /><h1>无法打开此会话</h1><p>{loadIssue.message}</p>
  </section>;
  if (runs.length === 0) return <section className="conversation-welcome">
    <span className="welcome-kicker">新对话</span>
    <h1>有什么可以一起推进的吗？</h1>
    <p>把需求、问题或要测试的 Agent 任务交给我。过程、工具调用和最终结果都会在当前会话里清楚呈现。</p>
    <div className="suggestion-grid" aria-label="建议提示">
      {welcomeSuggestions.map((suggestion) => <button key={suggestion} type="button" onClick={() => onSuggestionSelect?.(suggestion)}>{suggestion}</button>)}
    </div>
  </section>;
  return <section className="conversation-stream" aria-label="当前会话消息"><div className="conversation-date">本次对话</div>{runs.map((run) => <RunConversation key={run.runId} run={run} state={state} operations={operationsByRunId[run.runId] ?? []} desktopControls={desktopControls.filter((control) => control.sourceRunId === run.runId)} onCompleteDesktopControl={onCompleteDesktopControl} workspaceTarget={workspaceTarget} onWorkspaceSelect={onWorkspaceSelect} />)}</section>;
}
