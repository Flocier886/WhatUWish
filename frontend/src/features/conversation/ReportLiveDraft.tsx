import type { ReportLiveDraft } from '@/domain/runtime/types';
import styles from './ReportLiveDraft.module.css';

const outputTypeLabels: Record<ReportLiveDraft['outputType'], string> = {
  markdown: 'Markdown',
  html: 'HTML',
  ppt: 'HTML Slides',
};

/**
 * Report 生成中的临时正文只按纯文本呈现。
 * 完整 HTML/Slides 只能在正式 Artifact 生成后经受控 iframe Reader 打开。
 */
export function ReportLiveDraftView({ drafts }: { drafts: ReportLiveDraft[] }) {
  if (drafts.length === 0) return null;
  return <section className={styles.container} aria-label="报告生成草稿" aria-live="polite">
    {drafts.map((draft) => <article key={draft.toolInvocationId} className={styles.draft}>
      <header>
        <span>正在生成 {outputTypeLabels[draft.outputType]} 报告</span>
        <small>实时草稿，仅当前连接可见</small>
      </header>
      <pre>{draft.content}</pre>
      {draft.truncated && <p>草稿已按展示预算截断，完整内容以最终 Artifact 文件为准。</p>}
    </article>)}
  </section>;
}
