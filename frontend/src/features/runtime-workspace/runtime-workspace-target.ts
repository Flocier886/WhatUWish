export type RuntimeWorkspaceTarget =
  | { kind: 'operation'; runId: string; operationId: string }
  | { kind: 'stage'; runId: string; operationId: string; stageId: string }
  | { kind: 'artifact'; runId: string; artifactId: string }
  | { kind: 'genui'; runId: string; canvasId: string }
  | { kind: 'desktop'; runId: string; controlId: string };

export type RuntimeWorkspaceView = 'activity' | 'files' | 'desktop';

export function runtimeWorkspaceViewForTarget(target: RuntimeWorkspaceTarget): RuntimeWorkspaceView {
  return target.kind === 'artifact' ? 'files' : target.kind === 'desktop' ? 'desktop' : 'activity';
}

export function runtimeWorkspaceTargetKey(target: RuntimeWorkspaceTarget): string {
  if (target.kind === 'genui') return `${target.runId}:genui:${target.canvasId}`;
  if (target.kind === 'artifact') return `${target.runId}:artifact:${target.artifactId}`;
  if (target.kind === 'desktop') return `${target.runId}:desktop:${target.controlId}`;
  if (target.kind === 'stage') return `${target.runId}:operation:${target.operationId}:stage:${target.stageId}`;
  return `${target.runId}:operation:${target.operationId}`;
}
