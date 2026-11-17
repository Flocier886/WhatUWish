import { useEffect, useMemo, useState, type RefObject } from 'react';
import { ArrowDown, ImagePlus, Menu, Sparkles, X } from 'lucide-react';
import { useRef } from 'react';
import { useRuntimeConsole } from './useRuntimeConsole';
import { TaskComposer } from '@/features/agents/TaskComposer';
import { ConversationStream } from '@/features/conversation/ConversationStream';
import { SessionSidebar } from '@/features/sessions/SessionSidebar';
import { useFollowLatestOutput } from '@/features/conversation/useFollowLatestOutput';
import { projectRuntimeOperations } from '@/domain/runtime/runtime-operation';
import { RuntimeWorkspace } from '@/features/runtime-workspace/RuntimeWorkspace';
import type { RuntimeWorkspaceTarget } from '@/features/runtime-workspace/runtime-workspace-target';
import { useRuntimeWorkspaceSelection } from '@/features/runtime-workspace/useRuntimeWorkspaceSelection';
import { ImageGenerationWorkbench } from '@/features/image-generation-workbench/ImageGenerationWorkbench';
import { GenUiActionBridge } from '@/features/genui/GenUiActionBridge';
import { projectGenUiCanvases } from '@/features/genui/projectGenUiCanvases';

export function App() {
  const { state, sessions, activeEvents, startRun, selectSession, createSession, desktopControls, completeDesktopControl } = useRuntimeConsole();
  const [selectedAgentId, setSelectedAgentId] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [sessionSearchQuery, setSessionSearchQuery] = useState('');
  const [suggestedTask, setSuggestedTask] = useState('');
  const [workspaceWidth, setWorkspaceWidth] = useState(() => {
    const stored = typeof window === 'undefined' ? undefined : window.sessionStorage.getItem('runtime-workspace-width');
    const parsed = stored ? Number(stored) : 390;
    return Number.isFinite(parsed) ? Math.max(320, Math.min(620, parsed)) : 390;
  });
  const [workspaceCollapsed, setWorkspaceCollapsed] = useState(false);
  const [workspaceFocused, setWorkspaceFocused] = useState(false);
  const [showImageWorkbench, setShowImageWorkbench] = useState(false);
  const conversationRef = useRef<HTMLDivElement>(null);
  const mobileNavReturnFocusRef = useRef<HTMLElement | null>(null);
  const previousMobileNavOpenRef = useRef(false);
  const desktopSearchRef = useRef<HTMLInputElement>(null);
  const mobileSearchRef = useRef<HTMLInputElement>(null);
  const focusSearchOnMobileOpenRef = useRef(false);
  const { onScroll, hasUnreadOutput, returnToLatest } = useFollowLatestOutput(conversationRef, `${state.selectedRunId ?? ''}:${activeEvents.length}:${state.streamState}`);
  const sessionRunIds = useMemo(() => state.runIdsBySession[state.activeSessionId] ?? [], [state.activeSessionId, state.runIdsBySession]);
  const operationsByRunId = useMemo(() => Object.fromEntries(sessionRunIds.map((runId) => [
    runId,
    projectRuntimeOperations(state.eventsByRunId[runId] ?? [], state.artifactsById),
  ])), [sessionRunIds, state.artifactsById, state.eventsByRunId]);
  const genUiCanvasesByRunId = useMemo(() => Object.fromEntries(sessionRunIds.map((runId) => [
    runId, projectGenUiCanvases(state.eventsByRunId[runId] ?? []),
  ])), [sessionRunIds, state.eventsByRunId]);
  const workspaceTargets = useMemo<RuntimeWorkspaceTarget[]>(() => {
    const targets: RuntimeWorkspaceTarget[] = [];
    sessionRunIds.forEach((runId) => {
      genUiCanvasesByRunId[runId]?.forEach((canvas) => targets.push({ kind: 'genui', runId, canvasId: canvas.id }));
      desktopControls.filter((control) => control.sourceRunId === runId).forEach((control) =>
        targets.push({ kind: 'desktop', runId, controlId: control.controlId }));
      operationsByRunId[runId]?.forEach((operation) => {
        targets.push({ kind: 'operation', runId, operationId: operation.operationId });
        operation.stages.forEach((stage) => targets.push({ kind: 'stage', runId, operationId: operation.operationId, stageId: stage.stageId }));
      });
      Object.values(state.artifactsById).filter((artifact) => artifact.runId === runId).forEach((artifact) => targets.push({ kind: 'artifact', runId, artifactId: artifact.artifactId }));
    });
    return targets;
  }, [operationsByRunId, genUiCanvasesByRunId, sessionRunIds, state.artifactsById, desktopControls]);
  const latestActivityTarget = useMemo<RuntimeWorkspaceTarget | undefined>(() => {
    for (let index = sessionRunIds.length - 1; index >= 0; index -= 1) {
      const runId = sessionRunIds[index];
      const operations = operationsByRunId[runId];
      if (!operations?.length) continue;
      const operation = operations.at(-1)!;
      const stage = operation.stages.at(-1);
      return stage
        ? { kind: 'stage', runId, operationId: operation.operationId, stageId: stage.stageId }
        : { kind: 'operation', runId, operationId: operation.operationId };
    }
    return undefined;
  }, [operationsByRunId, sessionRunIds]);
  const latestArtifactTarget = useMemo<RuntimeWorkspaceTarget | undefined>(() => {
    for (let runIndex = sessionRunIds.length - 1; runIndex >= 0; runIndex -= 1) {
      const runId = sessionRunIds[runIndex];
      const events = state.eventsByRunId[runId] ?? [];
      for (let eventIndex = events.length - 1; eventIndex >= 0; eventIndex -= 1) {
        const event = events[eventIndex];
        if (event.eventType !== 'ARTIFACT_CREATED') continue;
        const artifactId = (event.payload as { artifactId?: string }).artifactId;
        if (artifactId && state.artifactsById[artifactId]) return { kind: 'artifact', runId, artifactId };
      }
    }
    return undefined;
  }, [sessionRunIds, state.artifactsById, state.eventsByRunId]);
  const workspace = useRuntimeWorkspaceSelection(state.activeSessionId, workspaceTargets, latestActivityTarget, latestArtifactTarget);
  const lastAutoOpenedDesktop = useRef('');
  const lastAutoOpenedCanvas = useRef('');

  useEffect(() => {
    if (mobileNavOpen && focusSearchOnMobileOpenRef.current) {
      mobileSearchRef.current?.focus();
      focusSearchOnMobileOpenRef.current = false;
    }
    if (previousMobileNavOpenRef.current && !mobileNavOpen) mobileNavReturnFocusRef.current?.focus({ preventScroll: true });
    previousMobileNavOpenRef.current = mobileNavOpen;
  }, [mobileNavOpen]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setCollapsed(false);
        if (window.matchMedia('(max-width: 760px)').matches) {
          mobileNavReturnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
          focusSearchOnMobileOpenRef.current = true;
          setMobileNavOpen(true);
        } else {
          window.requestAnimationFrame(() => desktopSearchRef.current?.focus());
        }
      } else if (event.key === 'Escape') {
        setMobileNavOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    const pending = desktopControls.find((control) => control.status === 'PENDING'
      && (control.holdUntil === undefined || control.holdUntil > Date.now() / 1000) && control.streamUrl);
    if (!pending) {
      lastAutoOpenedDesktop.current = '';
      return;
    }
    const key = `${state.activeSessionId}:${pending.controlId}`;
    if (lastAutoOpenedDesktop.current === key) return;
    lastAutoOpenedDesktop.current = key;
    setWorkspaceCollapsed(false);
    workspace.select({ kind: 'desktop', runId: pending.sourceRunId, controlId: pending.controlId });
  }, [desktopControls, state.activeSessionId, workspace]);

  useEffect(() => {
    for (let runIndex = sessionRunIds.length - 1; runIndex >= 0; runIndex -= 1) {
      const runId = sessionRunIds[runIndex];
      if (state.runsById[runId]?.isHistorical) continue;
      const publication = [...(operationsByRunId[runId] ?? [])].reverse()
        .find((operation) => operation.canvasPresentation?.data.openInPanel
          && state.artifactsById[operation.canvasPresentation.data.artifactId]);
      if (!publication?.canvasPresentation) continue;
      const artifactId = publication.canvasPresentation.data.artifactId;
      const key = state.activeSessionId + ":" + runId + ":" + publication.operationId;
      if (lastAutoOpenedCanvas.current === key) break;
      lastAutoOpenedCanvas.current = key;
      setWorkspaceCollapsed(false);
      workspace.select({ kind: 'artifact', runId, artifactId });
      break;
    }
  }, [operationsByRunId, sessionRunIds, state.activeSessionId, state.artifactsById, state.runsById, workspace]);

  const selectWorkspaceTarget = (target: RuntimeWorkspaceTarget) => {
    setWorkspaceCollapsed(false);
    workspace.select(target);
  };

  useEffect(() => {
    if (!selectedAgentId) setSelectedAgentId(state.agents.find((agent) => agent.availability === 'AVAILABLE' && agent.strategy === 'REACT')?.agentId);
  }, [selectedAgentId, state.agents]);

  const changeWorkspaceWidth = (width: number) => {
    setWorkspaceWidth(width);
    window.sessionStorage.setItem('runtime-workspace-width', String(Math.round(width)));
  };

  const submit = async (task: string, maxStep: number, fileIds?: string[]) => {
    if (!selectedAgentId) return false;
    setSubmitting(true);
    try { return await startRun({ agentId: selectedAgentId, sessionId: state.activeSessionId, task, maxStep, fileIds }); }
    finally { setSubmitting(false); }
  };

  const activeSessionTitle = sessions.find((session) => session.sessionId === state.activeSessionId)?.title ?? '新对话';
  const navigation = (searchInputRef: RefObject<HTMLInputElement | null>) => <SessionSidebar sessions={sessions} activeSessionId={state.activeSessionId} collapsed={collapsed} searchQuery={sessionSearchQuery} onSearchQueryChange={setSessionSearchQuery} searchInputRef={searchInputRef} onToggle={() => setCollapsed((value) => !value)} onCreate={() => { setMobileNavOpen(false); void createSession(); }} onSelect={(sessionId) => { void selectSession(sessionId); setMobileNavOpen(false); }} />;

  return <div className="chat-workspace">
    <GenUiActionBridge busy={submitting || state.streamState === 'STREAMING'} sendMessage={(message) => { void submit(message, 20); }} />
    <div className="chat-layout">
      {navigation(desktopSearchRef)}
      <div className="chat-content-shell">
        <header className="chat-topbar">
          <button className="mobile-nav-button" aria-label="打开会话导航" onClick={(event) => { mobileNavReturnFocusRef.current = event.currentTarget; setCollapsed(false); setMobileNavOpen(true); }}><Menu size={19} /></button>
          <div className="topbar-title"><span>{activeSessionTitle}</span><small>AI 生成内容仅供参考，请结合运行记录核验</small></div>
          <div className="topbar-actions">
            <button
              className={`topbar-tool-button ${showImageWorkbench ? 'topbar-tool-button-active' : ''}`}
              type="button"
              aria-pressed={showImageWorkbench}
              aria-label={showImageWorkbench ? '返回对话工作台' : '打开图片生成工作台'}
              title={showImageWorkbench ? '返回对话工作台' : '打开图片生成工作台'}
              onClick={() => setShowImageWorkbench((current) => !current)}
            ><ImagePlus size={16} />图片生成</button>
            <span className="credit-pill"><Sparkles size={15} /> 运行工作台</span>
          </div>
        </header>
        <main className="chat-main">
          {showImageWorkbench ? <ImageGenerationWorkbench sessionId={state.activeSessionId} /> : <div className={`runtime-content-grid ${workspaceFocused ? 'runtime-content-focused' : ''}`}>
            <div className="runtime-conversation-pane">
              <div className="conversation-scroll" ref={conversationRef} onScroll={onScroll}>
                <ConversationStream state={state} operationsByRunId={operationsByRunId} desktopControls={desktopControls} onCompleteDesktopControl={completeDesktopControl} workspaceTarget={workspace.target} onWorkspaceSelect={selectWorkspaceTarget} onSuggestionSelect={setSuggestedTask} />
              </div>
              {hasUnreadOutput && <button className="return-latest-button" type="button" onClick={returnToLatest}><ArrowDown size={15} /> 返回最新输出</button>}
              <TaskComposer agents={state.agents} selectedAgentId={selectedAgentId} sessionId={state.activeSessionId} onSelect={setSelectedAgentId} onCreateSession={createSession} submitting={submitting} onSubmit={submit} suggestedTask={suggestedTask} onSuggestedTaskUsed={() => setSuggestedTask('')} />
            </div>
            <RuntimeWorkspace
              view={workspace.view}
              target={workspace.target}
              following={workspace.following}
              hasNewActivity={workspace.hasNewActivity}
              sessionRunIds={sessionRunIds}
              operationsByRunId={operationsByRunId}
              genUiCanvasesByRunId={genUiCanvasesByRunId}
              artifactsById={state.artifactsById}
              desktopControls={desktopControls}
              onCompleteDesktopControl={completeDesktopControl}
              width={workspaceWidth}
              collapsed={workspaceCollapsed}
              focused={workspaceFocused}
              mobileOpen={workspace.mobileOpen}
              onMobileOpenChange={workspace.setMobileOpen}
              onMobileCloseAutoFocus={workspace.restoreMobileTriggerFocus}
              onSelect={selectWorkspaceTarget}
              onViewChange={workspace.selectView}
              onResumeFollowing={workspace.resumeFollowing}
              onWidthChange={changeWorkspaceWidth}
              onCollapsedChange={setWorkspaceCollapsed}
              onFocusedChange={setWorkspaceFocused}
            />
          </div>}
        </main>
      </div>
    </div>
    {mobileNavOpen && <><button type="button" className="mobile-session-backdrop" aria-label="点击遮罩关闭会话导航" onClick={() => setMobileNavOpen(false)} /><div className="mobile-session-layer" role="dialog" aria-modal="true" aria-label="会话导航">
      <button className="sheet-dismiss" aria-label="关闭会话导航" onClick={() => setMobileNavOpen(false)}><X size={18} /></button>
      {navigation(mobileSearchRef)}
    </div></>}
  </div>;
}
