import * as Dialog from '@radix-ui/react-dialog';
import { Activity, ArrowLeft, Download, FileText, Files, Focus, Maximize2, Monitor, PanelRightClose, PanelRightOpen, Pin, RotateCcw, X } from 'lucide-react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { SafeMarkdown } from '@/components/shared/SafeMarkdown';
import type { RuntimeOperation } from '@/domain/runtime/runtime-operation';
import { runtimeAssociationLabel, runtimeOperationStateLabel, runtimeStageStatusLabel, runtimeToolLabel } from '@/domain/runtime/runtime-operation-copy';
import type { ArtifactReference, DesktopControlPayload } from '@/domain/runtime/types';
import { DesktopPreviewPanel } from './DesktopPreviewPanel';
import { ArtifactPreviewContent } from '@/features/artifacts/ArtifactPreviewContent';
import { ArtifactPreviewDialog } from '@/features/artifacts/ArtifactPreviewDialog';
import { HttpArtifactContentDataSource } from '@/features/artifacts/gateway/HttpArtifactContentDataSource';
import type { RuntimeWorkspaceTarget, RuntimeWorkspaceView } from './runtime-workspace-target';
import type { GenUiCanvas } from '@/features/genui/projectGenUiCanvases';
import { GenUiCanvasView } from '@/features/genui/GenUiCanvasView';
import styles from './RuntimeWorkspace.module.css';

const artifactDataSource = new HttpArtifactContentDataSource();

interface RuntimeWorkspaceProps {
  view: RuntimeWorkspaceView;
  target?: RuntimeWorkspaceTarget;
  following: boolean;
  hasNewActivity: boolean;
  sessionRunIds: string[];
  operationsByRunId: Record<string, RuntimeOperation[]>;
  genUiCanvasesByRunId?: Record<string, GenUiCanvas[]>;
  artifactsById: Record<string, ArtifactReference>;
  desktopControls?: DesktopControlPayload[];
  onCompleteDesktopControl?: (controlId: string) => Promise<boolean>;
  width: number;
  collapsed: boolean;
  focused: boolean;
  mobileOpen: boolean;
  onMobileOpenChange: (open: boolean) => void;
  onMobileCloseAutoFocus: () => void;
  onSelect: (target: RuntimeWorkspaceTarget) => void;
  onViewChange: (view: RuntimeWorkspaceView) => void;
  onResumeFollowing: () => void;
  onWidthChange: (width: number) => void;
  onCollapsedChange: (collapsed: boolean) => void;
  onFocusedChange: (focused: boolean) => void;
}

function OperationDetails({ operation, runId, onSelect, showStageLinks = true }: { operation: RuntimeOperation; runId: string; onSelect: RuntimeWorkspaceProps['onSelect']; showStageLinks?: boolean }) {
  return <div className={styles.detailBody}>
    <dl className={styles.facts}>
      <div><dt>执行动作</dt><dd>{runtimeToolLabel(operation.toolName, operation.isRich)}</dd></div>
      <div><dt>状态</dt><dd>{runtimeOperationStateLabel(operation.state)}</dd></div>
      {operation.stepNo !== undefined && <div><dt>步骤</dt><dd>{operation.stepNo}</dd></div>}
      {operation.association && <div><dt>记录状态</dt><dd>{runtimeAssociationLabel(operation.association)}</dd></div>}
    </dl>
    {operation.lifecycleStartMissing && <p className={styles.notice}>该历史记录缺少开始事件，已按现有过程和结果事实展示。</p>}
    {operation.argumentsSummary && <section><h3>参数摘要</h3><p>{operation.argumentsSummary}</p></section>}
    {showStageLinks && operation.stages.length > 0 && <section><h3>过程阶段</h3><div className={styles.stageLinks}>{operation.stages.map((stage) => <button type="button" key={stage.stageId} onClick={() => onSelect({ kind: 'stage', runId, operationId: operation.operationId, stageId: stage.stageId })}><span><strong>{stage.label}</strong><small>{stage.summary}</small></span><span>查看</span></button>)}</div></section>}
    {operation.observation && <section><h3>工具结果摘要</h3><SafeMarkdown content={operation.observation} variant="compact" /></section>}
    {operation.errorMessage && <p className={styles.error}>{operation.errorMessage}</p>}
  </div>;
}

function StageDetails({ operation, stageId }: { operation: RuntimeOperation; stageId: string }) {
  const stage = operation.stages.find((item) => item.stageId === stageId);
  if (!stage) return <div className={styles.empty}>这个阶段已不在当前运行投影中。</div>;
  return <div className={styles.detailBody}>
    <dl className={styles.facts}><div><dt>过程类型</dt><dd>{runtimeToolLabel(operation.toolName, operation.isRich)}</dd></div><div><dt>阶段状态</dt><dd>{runtimeStageStatusLabel(stage.status)}</dd></div></dl>
    <section><h3>{stage.label}</h3><p>{stage.summary}</p></section>
    {stage.unavailable && <p className={styles.notice}>当前客户端不了解该版本的展示协议，原始数据不会直接显示。</p>}
    {stage.details.length > 0 && <div className={styles.sourceList}>{stage.details.map((item, index) => <article key={`${item.label}-${index}`}>
      <div><strong>{item.label}</strong>{item.description && <small>{item.description}</small>}{item.label === '代码'
        ? <pre className={styles.codeDetail}><code>{item.value}</code></pre>
        : <span>{item.value}</span>}</div>
      {item.href && <a href={item.href} target="_blank" rel="noreferrer noopener" aria-label={`打开来源 ${item.label}`}>查看来源</a>}
    </article>)}</div>}
  </div>;
}

function ArtifactDetails({ artifact, runId, onPreview }: { artifact: ArtifactReference; runId: string; onPreview: (trigger: HTMLButtonElement) => void }) {
  const downloadable = artifact.accessState !== 'NOT_FOUND' && artifact.accessState !== 'FORBIDDEN' && artifact.accessState !== 'UNAVAILABLE' && artifact.downloadSupported !== false;
  return <div className={styles.detailBody}>
    <div className={styles.fileHeading}><div><h3>{artifact.name}</h3><p>{artifact.summary || artifact.artifactType}</p></div><div className={styles.fileActions}>
      {artifact.previewSupported && <button type="button" aria-label={`放大阅读 ${artifact.name}`} title="放大阅读" onClick={(event) => onPreview(event.currentTarget)}><Maximize2 size={16} /></button>}
      {downloadable && <a href={artifactDataSource.buildDownloadHref(runId, artifact.artifactId)} aria-label={`下载文件 ${artifact.name}`} title="下载文件"><Download size={16} /></a>}
    </div></div>
    <ArtifactPreviewContent key={artifact.artifactId} artifact={artifact.runId ? artifact : { ...artifact, runId }} dataSource={artifactDataSource} className={styles.inlineReader} />
  </div>;
}

function resolveOperation(props: RuntimeWorkspaceProps): RuntimeOperation | undefined {
  const { target } = props;
  if (!target || target.kind === 'artifact' || target.kind === 'desktop' || target.kind === 'genui') return undefined;
  return props.operationsByRunId[target.runId]?.find((item) => item.operationId === target.operationId);
}

function ActivityWorkspaceContent({ props, mobile }: { props: RuntimeWorkspaceProps; mobile: boolean }) {
  const { target } = props;
  if (!target || target.kind === 'artifact') return <div className={styles.empty}>运行工具后，这里会跟随展示当前动态。</div>;
  if (target.kind === 'genui') {
    const canvas = props.genUiCanvasesByRunId?.[target.runId]?.find((item) => item.id === target.canvasId);
    return canvas ? <GenUiCanvasView canvas={canvas} /> : <div className={styles.empty}>画布当前不可用。</div>;
  }
  const operation = resolveOperation(props);
  if (!operation) return <div className={styles.empty}>这个工具操作已不在当前会话投影中。</div>;
  if (mobile || !operation.isRich || operation.stages.length === 0) {
    return target.kind === 'stage'
      ? <StageDetails operation={operation} stageId={target.stageId} />
      : <OperationDetails operation={operation} runId={target.runId} onSelect={props.onSelect} />;
  }

  const overviewLabel = operation.toolName === 'deep_search_tool' ? '研究概览' : `${runtimeToolLabel(operation.toolName, true)}概览`;
  return <div className={styles.activityWorkbench}>
    <section className={styles.operationOverview} aria-label={overviewLabel}>
      <div><span>{overviewLabel}</span><strong>{runtimeOperationStateLabel(operation.state)}</strong></div>
      <p>{operation.observation || operation.argumentsSummary || '正在整理研究过程与可验证来源。'}</p>
      <button type="button" className={target.kind === 'operation' ? styles.stageActive : ''} onClick={() => props.onSelect({ kind: 'operation', runId: target.runId, operationId: operation.operationId })}>查看总体结果与限制</button>
    </section>
    <nav className={styles.stageNavigator} aria-label={operation.toolName === 'deep_search_tool' ? '研究阶段' : '执行阶段'}>
      {operation.stages.map((stage, index) => <button
        type="button"
        key={stage.stageId}
        className={target.kind === 'stage' && target.stageId === stage.stageId ? styles.stageActive : ''}
        onClick={() => props.onSelect({ kind: 'stage', runId: target.runId, operationId: operation.operationId, stageId: stage.stageId })}
      ><span>{index + 1}</span><span><strong>{stage.label}</strong><small>{stage.summary}</small></span></button>)}
    </nav>
    <div className={styles.stageContent}>
      {target.kind === 'stage'
        ? <StageDetails operation={operation} stageId={target.stageId} />
        : <OperationDetails operation={operation} runId={target.runId} onSelect={props.onSelect} showStageLinks={false} />}
    </div>
  </div>;
}

function sessionArtifacts(props: RuntimeWorkspaceProps): ArtifactReference[] {
  const runIds = new Set(props.sessionRunIds);
  const artifacts = new Map<string, ArtifactReference>();
  Object.values(props.artifactsById).forEach((artifact) => {
    if (artifact.runId && runIds.has(artifact.runId)) artifacts.set(artifact.artifactId, artifact);
  });
  props.sessionRunIds.forEach((runId) => props.operationsByRunId[runId]?.forEach((operation) => {
    operation.artifactIds.forEach((artifactId) => {
      const artifact = props.artifactsById[artifactId];
      if (artifact) artifacts.set(artifactId, artifact.runId ? artifact : { ...artifact, runId });
    });
  }));
  return [...artifacts.values()];
}

function FilesWorkspaceContent(props: RuntimeWorkspaceProps) {
  const [previewArtifact, setPreviewArtifact] = useState<ArtifactReference>();
  const previewTriggerRef = useRef<HTMLButtonElement | undefined>(undefined);
  const artifacts = sessionArtifacts(props);
  if (artifacts.length === 0) return <div className={styles.empty}>当前会话还没有可交付文件。</div>;
  const selected = artifacts.find((artifact) => artifact.artifactId === (props.target?.kind === 'artifact' ? props.target.artifactId : '')) ?? artifacts.at(-1)!;
  return <div className={styles.filesWorkspace}>
    <div className={styles.filesSummary}><strong>会话文件</strong><span>{artifacts.length} 个</span></div>
    <nav className={styles.fileList} aria-label="会话文件">
      {artifacts.map((artifact) => <button type="button" key={artifact.artifactId} className={artifact.artifactId === selected.artifactId ? styles.fileSelected : ''} aria-current={artifact.artifactId === selected.artifactId ? 'true' : undefined} onClick={() => artifact.runId && props.onSelect({ kind: 'artifact', runId: artifact.runId, artifactId: artifact.artifactId })}>
        <FileText size={16} /><span><strong>{artifact.name}</strong><small>{artifact.accessState === 'NOT_FOUND' ? '已删除' : artifact.previewSupported ? '可预览' : '可下载'}</small></span>
      </button>)}
    </nav>
    <ArtifactDetails artifact={selected} runId={selected.runId ?? props.sessionRunIds.at(-1) ?? ''} onPreview={(trigger) => { previewTriggerRef.current = trigger; setPreviewArtifact(selected); }} />
    <ArtifactPreviewDialog artifact={previewArtifact} dataSource={artifactDataSource} open={Boolean(previewArtifact)} returnFocusRef={previewTriggerRef} onOpenChange={(open) => { if (!open) setPreviewArtifact(undefined); }} />
  </div>;
}

function WorkspaceContent({ props, mobile }: { props: RuntimeWorkspaceProps; mobile: boolean }) {
  if (props.view === 'desktop') {
    const control = props.desktopControls?.find((item) => item.controlId === (props.target?.kind === 'desktop' ? props.target.controlId : ''));
    return control ? <DesktopPreviewPanel control={control} onClose={props.onResumeFollowing}
      onComplete={props.onCompleteDesktopControl ?? (async () => false)} />
      : <div className={styles.empty}>桌面接管记录当前不可用。</div>;
  }
  if (props.view === 'files') return <FilesWorkspaceContent {...props} />;
  return <ActivityWorkspaceContent props={props} mobile={mobile} />;
}

function titleFor(props: RuntimeWorkspaceProps, mobile = false): string {
  const { target } = props;
  if (props.view === 'files') return target?.kind === 'artifact' ? props.artifactsById[target.artifactId]?.name ?? '文件交付' : '文件交付';
  if (props.view === 'desktop') return '桌面预览';
  if (!target) return '运行动态';
  if (target.kind === 'artifact') return props.artifactsById[target.artifactId]?.name ?? '文件详情';
  if (target.kind === 'desktop') return '桌面预览';
  if (target.kind === 'genui') return '交互画布';
  const operation = props.operationsByRunId[target.runId]?.find((item) => item.operationId === target.operationId);
  if (target.kind === 'stage') return mobile ? operation?.stages.find((item) => item.stageId === target.stageId)?.label ?? '阶段详情' : operation?.toolName === 'deep_search_tool' ? '深度研究动态' : `${runtimeToolLabel(operation?.toolName ?? '', true)}动态`;
  return operation?.isRich ? `${runtimeToolLabel(operation.toolName, true)}详情` : runtimeToolLabel(operation?.toolName ?? '工具详情');
}

function Header({ props, mobile = false }: { props: RuntimeWorkspaceProps; mobile?: boolean }) {
  const stageTarget = props.target?.kind === 'stage' ? props.target : undefined;
  const parentOperation = stageTarget
    ? props.operationsByRunId[stageTarget.runId]?.find((item) => item.operationId === stageTarget.operationId)
    : undefined;
  return <header className={styles.header}>
    <div className={styles.headerCopy}>
      {mobile && stageTarget
        ? <button
            type="button"
            className={styles.parentLink}
            onClick={() => props.onSelect({ kind: 'operation', runId: stageTarget.runId, operationId: stageTarget.operationId })}
          ><ArrowLeft size={13} /><span>返回{parentOperation?.toolName === 'deep_search_tool' ? '研究概览' : '工具概览'}</span></button>
        : <span>{props.view === 'files' ? '当前会话交付' : props.view === 'desktop' ? '用户接管' : props.following ? '正在跟随最新' : '已固定详情'}{!props.following && props.view === 'activity' && <Pin size={11} />}</span>}
      <h2>{titleFor(props, mobile)}</h2>
      {mobile && stageTarget && <small>{runtimeToolLabel(parentOperation?.toolName ?? '', parentOperation?.isRich)} / 过程阶段</small>}
    </div>
    <div className={styles.headerActions}>
      {(!props.following || props.hasNewActivity) && <button type="button" className={styles.resumeButton} onClick={props.onResumeFollowing} aria-label="恢复跟随最新" title="恢复跟随最新"><RotateCcw size={16} /><span>跟随最新</span></button>}
      {!mobile && <button type="button" onClick={() => props.onFocusedChange(!props.focused)} aria-label={props.focused ? '退出详情专注模式' : '进入详情专注模式'} title={props.focused ? '退出详情专注模式' : '进入详情专注模式'}><Focus size={16} /></button>}
      {mobile && <Dialog.Close aria-label="关闭运行详情" title="关闭运行详情"><X size={18} /></Dialog.Close>}
    </div>
  </header>;
}

function WorkspaceTabs(props: RuntimeWorkspaceProps) {
  return <div className={`${styles.viewTabs} ${props.desktopControls?.some((item) => item.status === 'PENDING') ? styles.withDesktop : ''}`} role="tablist" aria-label="工作台视图">
    <button type="button" role="tab" aria-selected={props.view === 'activity'} onClick={() => props.onViewChange('activity')}><Activity size={15} /><span>动态</span>{props.hasNewActivity && <i aria-label="有新动态" />}</button>
    <button type="button" role="tab" aria-selected={props.view === 'files'} onClick={() => props.onViewChange('files')}><Files size={15} /><span>文件</span><small>{sessionArtifacts(props).length}</small></button>
    {props.desktopControls?.some((item) => item.status === 'PENDING') && <button type="button" role="tab" aria-selected={props.view === 'desktop'} onClick={() => {
      const control = props.desktopControls?.find((item) => item.status === 'PENDING');
      if (control) props.onSelect({ kind: 'desktop', runId: control.sourceRunId, controlId: control.controlId });
    }}><Monitor size={15} /><span>桌面</span></button>}
  </div>;
}

export function RuntimeWorkspace(props: RuntimeWorkspaceProps) {
  const [compact, setCompact] = useState(() => typeof window !== 'undefined'
    && Boolean(window.matchMedia?.('(max-width: 980px)').matches));
  useEffect(() => {
    if (!window.matchMedia) return;
    const query = window.matchMedia('(max-width: 980px)');
    const update = () => setCompact(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  const beginResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    const startX = event.clientX;
    const startWidth = props.width;
    const move = (moveEvent: PointerEvent) => props.onWidthChange(Math.max(320, Math.min(620, startWidth + startX - moveEvent.clientX)));
    const end = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', end); };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
  };

  return <>
    <aside className={`${styles.desktop} ${props.collapsed ? styles.collapsed : ''}`} style={{ width: props.collapsed ? 48 : props.width }} aria-label="运行详情工作区">
      {!props.collapsed && <button type="button" className={styles.resizeHandle} aria-label="调整运行详情宽度" onPointerDown={beginResize} />}
      <button type="button" className={styles.collapseButton} onClick={() => props.onCollapsedChange(!props.collapsed)} aria-label={props.collapsed ? '展开运行详情' : '折叠运行详情'} title={props.collapsed ? '展开运行详情' : '折叠运行详情'}>{props.collapsed ? <PanelRightOpen size={18} /> : <PanelRightClose size={18} />}</button>
      {!props.collapsed && !compact && <><Header props={props} /><WorkspaceTabs {...props} /><div className={styles.scroll}><WorkspaceContent props={props} mobile={false} /></div></>}
    </aside>
    <Dialog.Root open={props.mobileOpen} onOpenChange={props.onMobileOpenChange}>
      <Dialog.Portal><Dialog.Overlay className={styles.overlay} /><Dialog.Content className={styles.mobile} aria-describedby={undefined} onCloseAutoFocus={(event) => { event.preventDefault(); props.onMobileCloseAutoFocus(); }}><Dialog.Title className={styles.visuallyHidden}>{titleFor(props, true)}</Dialog.Title><Header props={props} mobile /><WorkspaceTabs {...props} /><div className={styles.scroll}><WorkspaceContent props={props} mobile /></div></Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  </>;
}
