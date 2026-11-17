import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useRef, useState, type PropsWithChildren } from 'react';
import { createInitialState, runtimeReducer, type RuntimeAction } from '@/domain/runtime/event-reducer';
import type { DesktopControlPayload, RunReplay, RuntimeConsoleState, SessionSummary, StartRunCommand } from '@/domain/runtime/types';
import type { RuntimeDataSource } from '@/gateways/RuntimeDataSource';
import { RuntimeEventApiDataSource } from '@/gateways/RuntimeEventApiDataSource';

interface RuntimeContextValue {
  state: RuntimeConsoleState;
  startRun(command: StartRunCommand): Promise<boolean>;
  selectRun(runId: string): void;
  refreshAgents(): Promise<void>;
  refreshRuns(sessionId?: string): Promise<void>;
  loadReplay(runId: string): Promise<RunReplay | undefined>;
  sessions: SessionSummary[];
  selectSession(sessionId: string): Promise<void>;
  createSession(): Promise<void>;
  desktopControls: DesktopControlPayload[];
  completeDesktopControl(controlId: string): Promise<boolean>;
}

const RuntimeContext = createContext<RuntimeContextValue | undefined>(undefined);
const defaultDataSource = new RuntimeEventApiDataSource();
const activeSessionKey = 'runtime-active-session-id';

/** 刷新或新开标签页后恢复上次查看的会话；文件内容仍须经过独立令牌校验。 */
function initialRuntimeState() {
  let sessionId: string | null = null;
  try { sessionId = window.localStorage.getItem(activeSessionKey) || window.sessionStorage.getItem(activeSessionKey); }
  catch { /* 禁用存储时使用默认会话。 */ }
  return createInitialState(sessionId || undefined);
}

export function RuntimeProvider({ children, dataSource = defaultDataSource }: PropsWithChildren<{ dataSource?: RuntimeDataSource }>) {
  const [state, dispatch] = useReducer(runtimeReducer, undefined, initialRuntimeState);
  const controllers = useRef(new Map<string, AbortController>());
  const terminalRunIds = useRef(new Set<string>());
  const selectedSessionId = useRef(state.activeSessionId);
  const mounted = useRef(true);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [desktopControls, setDesktopControls] = useState<DesktopControlPayload[]>([]);

  useEffect(() => {
    try { window.localStorage.setItem(activeSessionKey, state.activeSessionId); }
    catch {
      try { window.sessionStorage.setItem(activeSessionKey, state.activeSessionId); }
      catch { /* 浏览器禁用存储时仍允许当前标签页继续使用。 */ }
    }
  }, [state.activeSessionId]);

  const dispatchIfMounted = useCallback((action: RuntimeAction) => {
    if (mounted.current) dispatch(action);
  }, []);

  const refreshAgents = useCallback(async () => {
    dispatchIfMounted({ type: 'AGENTS_LOADING' });
    try {
      dispatchIfMounted({ type: 'AGENTS_LOADED', agents: await dataSource.listAgents() });
    } catch {
      dispatchIfMounted({ type: 'AGENTS_FAILED', message: '无法加载可用 Agent，请稍后重试。' });
    }
  }, [dataSource, dispatchIfMounted]);

  useEffect(() => { void refreshAgents(); }, [refreshAgents]);

  const refreshSessions = useCallback(async () => {
    try {
      const loadedSessions = await dataSource.listSessions();
      if (mounted.current) setSessions(loadedSessions);
    } catch {
      dispatchIfMounted({ type: 'ISSUE_REPORTED', issue: { id: `sessions-${Date.now()}`, message: '无法加载会话列表，请稍后重试。' } });
    }
  }, [dataSource, dispatchIfMounted]);

  useEffect(() => { void refreshSessions(); }, [refreshSessions]);

  const refreshRuns = useCallback(async (sessionId = state.activeSessionId) => {
    try {
      dispatchIfMounted({ type: 'RUNS_LOADED', runs: await dataSource.listRuns(sessionId) });
    } catch {
      dispatchIfMounted({ type: 'ISSUE_REPORTED', issue: { id: `history-${Date.now()}`, message: '无法加载 session 历史 Run。' } });
    }
  }, [dataSource, dispatchIfMounted, state.activeSessionId]);

  useEffect(() => { void refreshRuns(); }, [refreshRuns]);

  const hydrateSession = useCallback(async (sessionId: string) => {
    try {
      const snapshot = await dataSource.getConversation(sessionId);
      if (selectedSessionId.current !== sessionId) return;
      setSessions((current) => [snapshot.session, ...current.filter((item) => item.sessionId !== sessionId)]
        .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)));
      dispatchIfMounted({ type: 'RUNS_LOADED', runs: snapshot.replays.map((replay) => replay.run) });
      snapshot.replays.forEach((replay) => dispatchIfMounted({ type: 'REPLAY_LOADED', run: replay.run, events: replay.events }));
      if (dataSource.listDesktopControls) {
        try {
          const controls = await dataSource.listDesktopControls(sessionId);
          if (selectedSessionId.current === sessionId) setDesktopControls(controls);
        } catch {
          if (selectedSessionId.current === sessionId) setDesktopControls([]);
        }
      }
    } catch {
      if (selectedSessionId.current === sessionId) {
        dispatchIfMounted({ type: 'ISSUE_REPORTED', issue: { id: `conversation-${Date.now()}`, sessionId,
          message: '无法加载此会话的历史记录，请稍后重试。' } });
      }
    }
  }, [dataSource, dispatchIfMounted]);

  useEffect(() => { void hydrateSession(state.activeSessionId); }, [hydrateSession, state.activeSessionId]);

  const runningReplayIds = (state.runIdsBySession[state.activeSessionId] ?? [])
    .filter((id) => {
      const run = state.runsById[id];
      return run && (run.status === 'RUNNING' || run.status === 'CONNECTING') && !controllers.current.has(id);
    }).join(',');

  // 页面重载后 POST SSE 已不存在；持续读取账本回放，直到 Run 落入终态。
  useEffect(() => {
    if (!runningReplayIds) return;
    const sessionId = state.activeSessionId;
    let stopped = false;
    let pending = false;
    const poll = async () => {
      if (pending || stopped) return;
      pending = true;
      try {
        for (const runId of runningReplayIds.split(',')) {
          if (controllers.current.has(runId)) continue;
          const replay = await dataSource.getRunReplay(runId);
          if (stopped || selectedSessionId.current !== sessionId) return;
          dispatchIfMounted({ type: 'REPLAY_LOADED', run: replay.run, events: replay.events });
        }
      } catch { /* 下一轮继续尝试；已显示的回放不受临时网络故障影响。 */ }
      finally { pending = false; }
    };
    const timer = window.setInterval(() => { void poll(); }, 2000);
    return () => { stopped = true; window.clearInterval(timer); };
  }, [dataSource, dispatchIfMounted, runningReplayIds, state.activeSessionId]);

  const recoverDisconnectedRun = useCallback(async (runId: string) => {
    try {
      const replay = await dataSource.getRunReplay(runId);
      dispatchIfMounted({ type: 'REPLAY_LOADED', run: replay.run, events: replay.events });
      const terminal = replay.events.some((event) => event.eventType === 'RUN_COMPLETED' || event.eventType === 'RUN_FAILED' || event.eventType === 'RUN_WAITING_USER_INPUT');
      dispatchIfMounted({
        type: 'STREAM_STATE_CHANGED',
        state: terminal ? 'IDLE' : 'CONNECTING',
        message: terminal ? undefined : '实时连接已断开，正在通过历史回放跟踪运行。',
      });
    } catch {
      dispatchIfMounted({ type: 'STREAM_STATE_CHANGED', state: 'ERROR', message: '运行输出连接已断开，且无法回补已提交的过程。' });
    }
  }, [dataSource, dispatchIfMounted]);

  const subscribe = useCallback((runId: string) => {
    controllers.current.get(runId)?.abort();
    const controller = new AbortController();
    controllers.current.set(runId, controller);
    terminalRunIds.current.delete(runId);
    dispatchIfMounted({ type: 'STREAM_STATE_CHANGED', state: 'CONNECTING' });
    void dataSource.subscribeRunEvents(runId, (event) => {
      if (event.eventType === 'RUN_COMPLETED' || event.eventType === 'RUN_FAILED' || event.eventType === 'RUN_WAITING_USER_INPUT') terminalRunIds.current.add(runId);
      if (event.eventType === 'DESKTOP_CONTROL') {
        const control = event.payload as DesktopControlPayload;
        setDesktopControls((current) => [...current.filter((item) => item.controlId !== control.controlId), control]);
      }
      dispatchIfMounted({ type: 'EVENT_RECEIVED', event });
    }, controller.signal).catch(() => {
      if (!controller.signal.aborted && !terminalRunIds.current.has(runId)) void recoverDisconnectedRun(runId);
    }).finally(() => controllers.current.delete(runId));
  }, [dataSource, dispatchIfMounted, recoverDisconnectedRun]);

  const startRun = useCallback(async (command: StartRunCommand) => {
    try {
      const run = await dataSource.startRun(command);
      dispatchIfMounted({ type: 'RUN_CREATED', run });
      void refreshSessions();
      subscribe(run.runId);
      return true;
    } catch (cause) {
      dispatchIfMounted({ type: 'STREAM_STATE_CHANGED', state: 'ERROR', message:
        cause instanceof Error ? cause.message : '无法创建新的 Run，请检查输入后重试。' });
      return false;
    }
  }, [dataSource, dispatchIfMounted, subscribe, refreshSessions]);

  const completeDesktopControl = useCallback(async (controlId: string) => {
    if (!dataSource.completeDesktopControl || !dataSource.resumeDesktopControl) return false;
    try {
      const control = await dataSource.completeDesktopControl(state.activeSessionId, controlId);
      setDesktopControls((current) => [...current.filter((item) => item.controlId !== controlId), control]);
      if (!control.resumeRequestId) throw new Error('服务端未返回续跑标识');
      const source = state.runsById[control.sourceRunId];
      if (!source) throw new Error('无法定位源 Run');
      const run = await dataSource.resumeDesktopControl(state.activeSessionId, control.resumeRequestId, source.agentId);
      dispatchIfMounted({ type: 'RUN_CREATED', run });
      subscribe(run.runId);
      return true;
    } catch (cause) {
      dispatchIfMounted({ type: 'ISSUE_REPORTED', issue: { id: `desktop-${Date.now()}`,
        message: cause instanceof Error ? cause.message : '桌面续跑失败' } });
      return false;
    }
  }, [dataSource, dispatchIfMounted, state.activeSessionId, state.runsById, subscribe]);

  const selectSession = useCallback(async (sessionId: string) => {
    selectedSessionId.current = sessionId;
    setDesktopControls([]);
    dispatchIfMounted({ type: 'SESSION_SELECTED', sessionId });
    await hydrateSession(sessionId);
  }, [dispatchIfMounted, hydrateSession]);

  const createSession = useCallback(async () => {
    try {
      const session = await dataSource.createSession();
      if (mounted.current) setSessions((current) => [session, ...current.filter((item) => item.sessionId !== session.sessionId)]);
      selectedSessionId.current = session.sessionId;
      dispatchIfMounted({ type: 'SESSION_SELECTED', sessionId: session.sessionId });
    } catch {
      dispatchIfMounted({ type: 'ISSUE_REPORTED', issue: { id: `create-session-${Date.now()}`, message: '无法创建新对话，请稍后重试。' } });
    }
  }, [dataSource, dispatchIfMounted]);

  const loadReplay = useCallback(async (runId: string) => {
    try {
      const replay = await dataSource.getRunReplay(runId);
      dispatchIfMounted({ type: 'REPLAY_LOADED', run: replay.run, events: replay.events });
      return replay;
    } catch {
      dispatchIfMounted({ type: 'ISSUE_REPORTED', issue: { id: `replay-${Date.now()}`, runId, message: '无法加载该 Run 的历史回放。' } });
      return undefined;
    }
  }, [dataSource, dispatchIfMounted]);

  useEffect(() => {
    mounted.current = true;
    const activeControllers = controllers.current;
    return () => {
      mounted.current = false;
      activeControllers.forEach((controller) => controller.abort());
    };
  }, []);

  const value = useMemo<RuntimeContextValue>(() => ({
    state,
    startRun,
    selectRun: (runId) => dispatchIfMounted({ type: 'RUN_SELECTED', runId }),
    refreshAgents,
    refreshRuns,
    loadReplay,
    sessions,
    selectSession,
    createSession,
    desktopControls,
    completeDesktopControl,
  }), [state, startRun, dispatchIfMounted, refreshAgents, refreshRuns, loadReplay, sessions, selectSession, createSession, desktopControls, completeDesktopControl]);

  return <RuntimeContext.Provider value={value}>{children}</RuntimeContext.Provider>;
}

export function useRuntimeContext(): RuntimeContextValue {
  const context = useContext(RuntimeContext);
  if (!context) throw new Error('useRuntimeContext 必须在 RuntimeProvider 中使用。');
  return context;
}
