import { Activity, ChevronDown, CircleCheck, CircleDashed, CircleX, ExternalLink, FileText, Globe, Image, Search, Terminal, Wrench } from 'lucide-react';
import { useState } from 'react';
import { SafeMarkdown } from '@/components/shared/SafeMarkdown';
import type { RuntimeOperation, RuntimeOperationStage } from '@/domain/runtime/runtime-operation';
import { runtimeOperationStateLabel, runtimeToolLabel } from '@/domain/runtime/runtime-operation-copy';
import type { RuntimeWorkspaceTarget } from '@/features/runtime-workspace/runtime-workspace-target';
import styles from './RuntimeOperationList.module.css';

function StateIcon({ state }: { state: RuntimeOperation['state'] }) {
  if (state === 'RUNNING') return <CircleDashed className={styles.spinning} size={15} />;
  if (state === 'FAILED') return <CircleX size={15} />;
  return <CircleCheck size={15} />;
}

function ToolIcon({ name }: { name: string }) {
  if (name === 'deep_search_tool' || name === 'web_search') return <Search size={16} />;
  if (name === 'web_fetch' || name === 'browser' || name === 'agent_browser') return <Globe size={16} />;
  if (name === 'report_tool' || name === 'canvas_publish') return <FileText size={16} />;
  if (name === 'image_generation_tool') return <Image size={16} />;
  if (name === 'bash' || name === 'host_cli' || name === 'code_execution' || name === 'code_interpreter') return <Terminal size={16} />;
  return <Wrench size={16} />;
}

function elapsed(operation: RuntimeOperation): string | undefined {
  if (!operation.startedAt || operation.state === 'RUNNING') return undefined;
  const milliseconds = Date.parse(operation.updatedAt) - Date.parse(operation.startedAt);
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return undefined;
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(1)} s`;
}

interface RuntimeOperationListProps {
  runId: string;
  operations: RuntimeOperation[];
  active?: boolean;
  selected?: RuntimeWorkspaceTarget;
  onSelect: (target: RuntimeWorkspaceTarget) => void;
}

function StageItem({ stage, operation, runId, onSelect }: { stage: RuntimeOperationStage; operation: RuntimeOperation; runId: string; onSelect: RuntimeOperationListProps['onSelect'] }) {
  const [open, setOpen] = useState(false);
  return <li className={styles.stageItem}>
    <div className={styles.stageHead}>
      <button type="button" className={styles.stageMain} aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        <span className={styles.stageDot} aria-hidden="true" />
        <span className={styles.stageCopy}><strong>{stage.label}</strong><small>{stage.summary}</small></span>
        <ChevronDown size={14} />
      </button>
      <button type="button" className={styles.openWorkspace} aria-label={`在工作区查看阶段 ${stage.label}`} title="在工作区查看" onClick={() => onSelect({ kind: 'stage', runId, operationId: operation.operationId, stageId: stage.stageId })}><ExternalLink size={14} /></button>
    </div>
    {open && <div className={styles.stageDetails}>
      {stage.unavailable && <p>当前客户端不了解该版本的展示协议。</p>}
      {stage.details.length === 0 && <p>{stage.summary}</p>}
      {stage.details.map((detail, index) => <div className={styles.detailRow} key={`${detail.label}-${index}`}>
        <strong>{detail.label}</strong>
        <span>{detail.description || detail.value}</span>
        {detail.description && detail.value && <small>{detail.value}</small>}
        {detail.href && <a href={detail.href} target="_blank" rel="noopener noreferrer" aria-label={`打开来源 ${detail.label}`}><ExternalLink size={14} />查看来源</a>}
      </div>)}
    </div>}
  </li>;
}

function OperationItem({ operation, runId, selected, onSelect }: { operation: RuntimeOperation; runId: string; selected?: RuntimeWorkspaceTarget; onSelect: RuntimeOperationListProps['onSelect'] }) {
  const [manualOpen, setManualOpen] = useState<boolean>();
  const open = manualOpen ?? (operation.state === 'RUNNING' && operation.isRich);
  const label = runtimeToolLabel(operation.toolName, operation.isRich);
  const selectedHere = selected?.runId === runId && 'operationId' in selected && selected.operationId === operation.operationId;
  const summary = operation.argumentsSummary || operation.stages.at(-1)?.summary || operation.errorMessage || '';
  return <li className={`${styles.toolItem} ${selectedHere ? styles.selected : ''} ${operation.state === 'FAILED' ? styles.failed : ''}`}>
    <div className={styles.toolHead}>
      <button type="button" className={styles.toolMain} aria-expanded={open} onClick={() => setManualOpen(!open)}>
        <span className={styles.toolIcon}><ToolIcon name={operation.toolName} /></span>
        <span className={styles.toolCopy}><strong>{label}</strong>{summary && <small title={summary}>{summary}</small>}</span>
        <span className={styles.toolStatus}><StateIcon state={operation.state} /><span>{runtimeOperationStateLabel(operation.state)}</span></span>
        {elapsed(operation) && <time className={styles.duration}>{elapsed(operation)}</time>}
        <ChevronDown className={styles.chevron} size={15} />
      </button>
      <button type="button" className={styles.openWorkspace} aria-label={`在工作区查看 ${label}`} title="在工作区查看" onClick={() => onSelect({ kind: 'operation', runId, operationId: operation.operationId })}><ExternalLink size={15} /></button>
    </div>
    {open && <div className={styles.toolDetails}>
      {operation.lifecycleStartMissing && <p className={styles.missingStart}>历史记录缺少开始事件，现按已有结果展示。</p>}
      {operation.argumentsSummary && <div className={styles.detailSection}><strong>参数</strong><p>{operation.argumentsSummary}</p></div>}
      {operation.stages.length > 0 && <ol className={styles.stageList}>{operation.stages.map((stage) => <StageItem key={stage.stageId} stage={stage} operation={operation} runId={runId} onSelect={onSelect} />)}</ol>}
      {operation.canvasPresentation && <button type="button" className={styles.openWorkspace}
        onClick={() => onSelect({ kind: 'artifact', runId, artifactId: operation.canvasPresentation!.data.artifactId })}
        aria-label={"打开画布 " + operation.canvasPresentation.data.title}>
        <ExternalLink size={14} /> 打开画布：{operation.canvasPresentation.data.title}
      </button>}
      {operation.observation && <div className={styles.detailSection}><strong>结果</strong><div className={styles.observation}><SafeMarkdown content={operation.observation} variant="compact" /></div></div>}
      {operation.errorMessage && <p className={styles.error}>{operation.errorMessage}</p>}
      {!operation.observation && !operation.errorMessage && operation.stages.length === 0 && <p className={styles.waiting}>{operation.state === 'RUNNING' ? '等待工具输出…' : '暂无可展示的工具结果。'}</p>}
    </div>}
  </li>;
}

export function RuntimeOperationList({ runId, operations, active = false, selected, onSelect }: RuntimeOperationListProps) {
  const [manualOpen, setManualOpen] = useState<boolean>();
  if (operations.length === 0) return null;
  const open = manualOpen ?? true;
  const failed = operations.filter((operation) => operation.state === 'FAILED').length;
  const running = operations.find((operation) => operation.state === 'RUNNING');
  const latest = running ?? operations.at(-1)!;
  const summary = running ? `正在${runtimeToolLabel(latest.toolName, latest.isRich)}` : `${operations.length} 项操作${failed ? `，${failed} 项未完成` : '已完成'}`;

  return <section className={styles.processGroup} aria-label="本次执行过程">
    <button type="button" className={styles.groupHeader} aria-expanded={open} onClick={() => setManualOpen(!open)}>
      <Activity size={16} /><strong>{active ? '正在执行任务' : '执行过程'}</strong><span>{summary}</span><ChevronDown size={15} />
    </button>
    {open && <ol className={styles.toolList}>{operations.map((operation) => <OperationItem key={operation.operationId} operation={operation} runId={runId} selected={selected} onSelect={onSelect} />)}</ol>}
  </section>;
}
