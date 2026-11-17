import type { AgentSummary, ConversationSnapshot, DesktopControlPayload, ReportContentDeltaPayload, RunReplay, RunSummary, RuntimeEvent, SessionSummary, StartRunCommand, ToolProgressPayload } from '@/domain/runtime/types';
import type { RuntimeDataSource } from './RuntimeDataSource';
import { apiUrl } from './api-base';

type ApiResponse<T> = { code?: string | number; info?: string; data?: T };
type RuntimeAgentResponse = {
  agentId?: unknown;
  displayName?: unknown;
  description?: unknown;
  runtimeKind?: unknown;
  available?: unknown;
};
type RuntimeSessionResponse = { sessionId?: unknown; title?: unknown; updatedAt?: unknown; runCount?: unknown; latestQueryText?: unknown };
type RuntimeRunResponse = {
  runId?: unknown; sessionId?: unknown; requestId?: unknown; agentId?: unknown; task?: unknown; status?: unknown;
  startedAt?: unknown; finishedAt?: unknown; summary?: unknown; errorCode?: unknown; errorMessage?: unknown;
  historical?: unknown; dataIncomplete?: unknown; events?: unknown;
};
type RuntimeSessionHistoryResponse = { session?: RuntimeSessionResponse; replays?: RuntimeRunResponse[]; historical?: unknown };
type IssuedFileSession = { sessionId?: unknown };

const eventTypes = new Set<RuntimeEvent['eventType']>([
  'RUN_STARTED', 'INPUT_FILE', 'RUN_STATUS', 'LLM_RESPONSE', 'TOOL_STARTED', 'TOOL_PROGRESS', 'REPORT_CONTENT_DELTA', 'TOOL_RESULT', 'ARTIFACT_CREATED', 'DESKTOP_CONTROL', 'RUN_WAITING_USER_INPUT', 'RUN_COMPLETED', 'RUN_FAILED',
]);

function ensureRuntimeEvent(candidate: unknown, run: RunSummary): RuntimeEvent {
  if (!candidate || typeof candidate !== 'object') throw new Error('收到的运行事件不是对象。');
  const event = candidate as RuntimeEvent;
  if (event.schemaVersion !== 'v1' || !event.eventId || !eventTypes.has(event.eventType) || !Number.isInteger(event.sequence) || event.sequence < 1) {
    throw new Error('收到的运行事件不符合 Runtime Event v1 协议。');
  }
  if (event.runId !== run.runId || event.requestId !== run.requestId || event.sessionId !== run.sessionId || event.agentId !== run.agentId) {
    throw new Error('收到的运行事件与当前连接关联信息不一致。');
  }
  if (event.eventType === 'TOOL_PROGRESS') ensureToolProgress(event);
  if (event.eventType === 'REPORT_CONTENT_DELTA') ensureReportContentDelta(event);
  return event;
}

function ensureReportContentDelta(event: RuntimeEvent): void {
  const payload = event.payload as Partial<ReportContentDeltaPayload>;
  const invalid = !payload
    || payload.schemaVersion !== 'report.content_delta.v1'
    || !stringValue(payload.toolInvocationId).trim()
    || !stringValue(payload.toolName).trim()
    || !['markdown', 'html', 'ppt'].includes(String(payload.outputType))
    || !Number.isInteger(payload.chunkIndex) || Number(payload.chunkIndex) < 1
    || typeof payload.content !== 'string' || !payload.content.trim() || payload.content.length > 4000;
  if (invalid) throw new Error('收到的 Report 正文增量不符合受控 Live 协议。');
}

function ensureToolProgress(event: RuntimeEvent): void {
  const payload = event.payload as Partial<ToolProgressPayload>;
  const invalid = !payload
    || payload.runtimeEventId !== event.eventId
    || !stringValue(payload.toolInvocationId)
    || !stringValue(payload.toolName)
    || !stringValue(payload.stage)
    || !stringValue(payload.status)
    || !stringValue(payload.message)
    || !stringValue(payload.presentationKind)
    || !stringValue(payload.presentationSchemaVersion)
    || !payload.presentationData
    || typeof payload.presentationData !== 'object'
    || Array.isArray(payload.presentationData);
  if (invalid) throw new Error('收到的工具过程事件缺少受控展示所需字段。');
}

function splitFrames(source: string): { frames: string[]; rest: string } {
  const normalized = source.replace(/\r\n/g, '\n');
  const frames = normalized.split('\n\n');
  return { frames: frames.slice(0, -1), rest: frames.at(-1) ?? '' };
}

function parseFrame(frame: string): { event?: string; data?: string } | undefined {
  const lines = frame.split('\n');
  if (lines.every((line) => !line || line.startsWith(':'))) return undefined;
  let event: string | undefined;
  const data: string[] = [];
  for (const line of lines) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  return { event, data: data.join('\n') || undefined };
}

/** Runtime Event 目录由后端运行时拥有，不读取旧 ai_agent 表的 strategy/status 字段。 */
function mapRuntimeAgent(item: RuntimeAgentResponse): AgentSummary {
  return {
    agentId: String(item.agentId ?? ''),
    name: String(item.displayName ?? item.agentId ?? 'Runtime Agent'),
    description: typeof item.description === 'string' ? item.description : undefined,
    strategy: String(item.runtimeKind ?? '').trim().toUpperCase() === 'REACT' ? 'REACT' : 'LEGACY',
    availability: item.available === true ? 'AVAILABLE' : 'UNAVAILABLE',
  };
}

function stringValue(value: unknown, fallback = ''): string { return typeof value === 'string' ? value : fallback; }
function numberValue(value: unknown, fallback = 0): number { return typeof value === 'number' && Number.isFinite(value) ? value : fallback; }
function dateValue(value: unknown): string | undefined { return typeof value === 'string' && Number.isFinite(Date.parse(value)) ? value : undefined; }

function mapSession(item: RuntimeSessionResponse): SessionSummary {
  const sessionId = stringValue(item.sessionId);
  if (!sessionId) throw new Error('历史会话缺少 sessionId。');
  return { sessionId, title: stringValue(item.title, stringValue(item.latestQueryText, '新建对话')),
    updatedAt: dateValue(item.updatedAt) ?? new Date(0).toISOString(), runCount: numberValue(item.runCount) };
}

function mapHistoricalRun(item: RuntimeRunResponse): RunReplay {
  const runId = stringValue(item.runId); const sessionId = stringValue(item.sessionId);
  const requestId = stringValue(item.requestId); const agentId = stringValue(item.agentId);
  if (!runId || !sessionId || !requestId || !agentId) throw new Error('历史 Run 缺少稳定关联字段。');
  const run: RunSummary = { runId, sessionId, requestId, agentId, task: stringValue(item.task),
    status: item.status === 'FAILED' ? 'FAILED' : item.status === 'COMPLETED' ? 'COMPLETED'
      : item.status === 'WAITING_USER_INPUT' ? 'WAITING_USER_INPUT' : 'RUNNING',
    startedAt: dateValue(item.startedAt), finishedAt: dateValue(item.finishedAt), summary: stringValue(item.summary) || undefined,
    error: stringValue(item.errorMessage) ? { code: stringValue(item.errorCode) || undefined, message: stringValue(item.errorMessage) } : undefined,
    isHistorical: item.historical !== false, dataIncomplete: item.dataIncomplete === true };
  const candidates = Array.isArray(item.events) ? item.events : [];
  return { run, events: candidates.map((event) => ensureRuntimeEvent(event, run)) };
}

async function readApi<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => undefined) as ApiResponse<T> | undefined;
  if (!response.ok) throw new Error(body?.info || `历史请求失败（HTTP ${response.status}）。`);
  if (!body || body.code !== '0000' || body.data === undefined) throw new Error(body?.info || '历史接口返回了无效数据。');
  return body.data;
}

/** 正式 Runtime Event v1 数据源：不读取数据库、文件存储或 Replay。 */
export class RuntimeEventApiDataSource implements RuntimeDataSource {
  private readonly streams = new Map<string, { response: Response; run: RunSummary }>();
  private readonly sessions = new Map<string, SessionSummary>();

  async listAgents(): Promise<AgentSummary[]> {
    const response = await fetch(apiUrl('/api/v1/runtime-events/agents'), { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`加载 Agent 失败（HTTP ${response.status}）。`);
    const body = await response.json() as ApiResponse<RuntimeAgentResponse[]>;
    if (!Array.isArray(body.data)) throw new Error(body.info || 'Runtime Agent 目录返回了无效数据。');
    return body.data.map(mapRuntimeAgent).filter((agent) => Boolean(agent.agentId));
  }

  /** Run 请求只提交有序 fileId；后端负责重新校验文件归属与状态。 */
  async startRun(command: StartRunCommand): Promise<RunSummary> {
    const response = await fetch(apiUrl('/api/v1/runtime-events/run'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ aiAgentId: command.agentId, message: command.task, sessionId: command.sessionId,
        maxStep: command.maxStep, fileIds: command.fileIds ?? [] }),
    });
    if (!response.ok || !response.body) {
      const failure = await response.json().catch(() => undefined) as ApiResponse<unknown> | undefined;
      throw new Error(failure?.info && failure.info !== failure.code
        ? failure.info : `无法建立运行输出连接（HTTP ${response.status}）。`);
    }
    const runId = response.headers.get('X-Run-Id');
    const requestId = response.headers.get('X-Request-Id');
    if (!runId || !requestId) {
      await response.body.cancel();
      throw new Error('运行输出连接缺少 Run 关联信息。');
    }
    const run: RunSummary = {
      runId,
      requestId,
      sessionId: command.sessionId,
      agentId: command.agentId,
      task: command.task.trim(),
      status: 'CONNECTING',
      startedAt: new Date().toISOString(),
    };
    this.streams.set(runId, { response, run });
    const session = this.ensureSession(command.sessionId, command.task);
    this.sessions.set(command.sessionId, {
      ...session,
      runCount: session.runCount + 1,
      messageCount: (session.messageCount ?? 0) + 1,
      updatedAt: new Date().toISOString(),
    });
    return run;
  }

  async subscribeRunEvents(runId: string, listener: (event: RuntimeEvent) => void, signal: AbortSignal): Promise<void> {
    const connection = this.streams.get(runId);
    if (!connection) throw new Error('未找到待消费的运行输出连接。');
    const reader = connection.response.body?.getReader();
    if (!reader) throw new Error('运行输出响应不可读取。');
    const abort = () => { void reader.cancel(); };
    signal.addEventListener('abort', abort, { once: true });
    try {
      const decoder = new TextDecoder();
      let pending = '';
      let terminal = false;
      const deliver = (event: RuntimeEvent) => {
        listener(event);
        terminal = event.eventType === 'RUN_COMPLETED' || event.eventType === 'RUN_FAILED' || event.eventType === 'RUN_WAITING_USER_INPUT';
      };
      while (!signal.aborted) {
        const part = await reader.read();
        if (part.done) break;
        const split = splitFrames(pending + decoder.decode(part.value, { stream: true }));
        pending = split.rest;
        split.frames.forEach((rawFrame) => {
          const frame = parseFrame(rawFrame);
          if (!frame || frame.event !== 'runtime-event' || !frame.data) return;
          deliver(ensureRuntimeEvent(JSON.parse(frame.data), connection.run));
        });
      }
      const tail = decoder.decode();
      const finalFrame = parseFrame(pending + tail);
      if (finalFrame?.event === 'runtime-event' && finalFrame.data) deliver(ensureRuntimeEvent(JSON.parse(finalFrame.data), connection.run));
      if (!signal.aborted && !terminal) throw new Error('运行输出连接在收到终态前关闭。');
    } finally {
      signal.removeEventListener('abort', abort);
      this.streams.delete(runId);
      reader.releaseLock();
    }
  }

  /** 本地单用户历史由后端账本统一列出。 */
  async listSessions(): Promise<SessionSummary[]> {
    const response = await fetch(apiUrl('/api/v1/runtime-events/sessions?limit=50'), { headers: { Accept: 'application/json' } });
    const remote = (await readApi<RuntimeSessionResponse[]>(response)).map(mapSession);
    const remoteIds = new Set(remote.map((session) => session.sessionId));
    remote.forEach((session) => this.sessions.set(session.sessionId, session));
    // 新 Run 的 Session 可能尚在后端异步落账，列表刷新不能因此丢掉当前窗口。
    const localOnly = [...this.sessions.values()].filter((session) => !remoteIds.has(session.sessionId));
    return [...remote, ...localOnly].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }

  /** 通过后端签发文件会话，允许用户先上传附件、再创建 Agent Run。 */
  async createSession(): Promise<SessionSummary> {
    const response = await fetch(apiUrl('/api/v1/conversation-files/sessions'), { method: 'POST', headers: { Accept: 'application/json' } });
    const issued = await readApi<IssuedFileSession>(response);
    const sessionId = stringValue(issued.sessionId);
    if (!sessionId) throw new Error('文件会话签发结果无效。');
    const session = { sessionId, title: '新建对话', updatedAt: new Date().toISOString(), runCount: 0, messageCount: 0 };
    this.sessions.set(sessionId, session);
    return session;
  }

  /** 历史读取由后端统一投影；Replay 中的输入文件展示只包含安全元数据。 */
  async getConversation(sessionId: string): Promise<ConversationSnapshot> {
    const pending = this.sessions.get(sessionId);
    const response = await fetch(apiUrl(`/api/v1/runtime-events/sessions/${encodeURIComponent(sessionId)}?limit=100`), { headers: { Accept: 'application/json' } });
    if (response.status === 404 && pending && pending.runCount === 0) return { session: pending, replays: [] };
    const detail = await readApi<RuntimeSessionHistoryResponse>(response);
    if (!detail?.session || !Array.isArray(detail.replays)) throw new Error('历史会话详情缺少必要字段。');
    const session = mapSession(detail.session);
    if (session.sessionId !== sessionId) throw new Error('历史会话详情与请求的 sessionId 不一致。');
    const replays = detail.replays.map(mapHistoricalRun);
    if (replays.some((replay) => replay.run.sessionId !== sessionId)) throw new Error('历史会话包含了其他 Session 的 Run。');
    this.sessions.set(sessionId, session);
    return { session, replays };
  }

  async listRuns(sessionId: string): Promise<RunSummary[]> {
    const conversation = await this.getConversation(sessionId);
    return conversation.replays.map((replay) => replay.run);
  }

  async getRunReplay(runId: string): Promise<RunReplay> {
    const response = await fetch(apiUrl(`/api/v1/runtime-events/runs/${encodeURIComponent(runId)}`), { headers: { Accept: 'application/json' } });
    const replay = mapHistoricalRun(await readApi<RuntimeRunResponse>(response));
    if (replay.run.runId !== runId) throw new Error('历史 Run 详情与请求的 runId 不一致。');
    return replay;
  }

  async listDesktopControls(sessionId: string): Promise<DesktopControlPayload[]> {
    const response = await fetch(apiUrl(`/api/v1/runtime-events/sessions/${encodeURIComponent(sessionId)}/desktop-controls`),
      { headers: { Accept: 'application/json' } });
    return readApi<DesktopControlPayload[]>(response);
  }

  async completeDesktopControl(sessionId: string, controlId: string): Promise<DesktopControlPayload> {
    const response = await fetch(apiUrl(`/api/v1/runtime-events/sessions/${encodeURIComponent(sessionId)}/desktop-controls/${encodeURIComponent(controlId)}/complete`), {
      method: 'POST', headers: { Accept: 'application/json' },
    });
    return readApi<DesktopControlPayload>(response);
  }

  async resumeDesktopControl(sessionId: string, resumeRequestId: string, agentId: string): Promise<RunSummary> {
    const response = await fetch(apiUrl(`/api/v1/runtime-events/sessions/${encodeURIComponent(sessionId)}/desktop-controls/resume`), {
      method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ resumeRequestId }),
    });
    if (!response.ok || !response.body) throw new Error(`无法连接桌面续跑（HTTP ${response.status}）。`);
    const runId = response.headers.get('X-Run-Id');
    const requestId = response.headers.get('X-Request-Id');
    if (!runId || !requestId) {
      await response.body.cancel();
      throw new Error('续跑连接缺少 Run 关联信息。');
    }
    const run: RunSummary = { runId, requestId, sessionId, agentId, task: '', status: 'CONNECTING',
      startedAt: new Date().toISOString() };
    this.streams.set(runId, { response, run });
    return run;
  }

  private ensureSession(sessionId: string, task = '新建对话'): SessionSummary {
    const current = this.sessions.get(sessionId);
    const next = {
      sessionId,
      title: current?.runCount ? current.title : task.slice(0, 22),
      updatedAt: new Date().toISOString(),
      runCount: current?.runCount ?? 0,
      messageCount: current?.messageCount ?? 0,
    };
    this.sessions.set(sessionId, next);
    return next;
  }
}
