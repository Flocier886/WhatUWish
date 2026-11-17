import type { AgentSummary, ConversationSnapshot, DesktopControlPayload, RunReplay, RunSummary, RuntimeEvent, SessionSummary, StartRunCommand } from '@/domain/runtime/types';

export interface RuntimeDataSource {
  listSessions(): Promise<SessionSummary[]>;
  createSession(): Promise<SessionSummary>;
  getConversation(sessionId: string): Promise<ConversationSnapshot>;
  listAgents(): Promise<AgentSummary[]>;
  startRun(command: StartRunCommand): Promise<RunSummary>;
  subscribeRunEvents(runId: string, listener: (event: RuntimeEvent) => void, signal: AbortSignal): Promise<void>;
  listRuns(sessionId: string): Promise<RunSummary[]>;
  getRunReplay(runId: string): Promise<RunReplay>;
  listDesktopControls?(sessionId: string): Promise<DesktopControlPayload[]>;
  completeDesktopControl?(sessionId: string, controlId: string): Promise<DesktopControlPayload>;
  resumeDesktopControl?(sessionId: string, resumeRequestId: string, agentId: string): Promise<RunSummary>;
}
