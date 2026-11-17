import type { ArtifactRendererProps } from './MarkdownArtifactRenderer';
import styles from './SandboxedHtmlArtifactRenderer.module.css';

const CSP = [
  "default-src 'none'",
  "img-src data: blob: https:",
  "style-src 'unsafe-inline' https://unpkg.com https://cdn.jsdelivr.net",
  "font-src data: https://unpkg.com https://cdn.jsdelivr.net",
  "script-src 'unsafe-inline' https://unpkg.com https://cdn.jsdelivr.net",
  "connect-src 'none'",
  "media-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-src 'none'",
].join('; ');

function isolatedDocument(content: string): string {
  const policy = `<meta http-equiv="Content-Security-Policy" content="${CSP}">`;
  const documentStart = content.search(/<!doctype\s+html|<html[\s>]/iu);
  // 即使模型在文档前误输出了说明或推理文本，也只能把 HTML 文档本身交给 iframe；
  // 否则浏览器会将 meta 当作 body 内容而忽略 CSP。
  const documentContent = documentStart >= 0 ? content.slice(documentStart) : content;
  if (/<head[\s>]/iu.test(documentContent)) return documentContent.replace(/<head([^>]*)>/iu, `<head$1>${policy}`);
  // 模型有时会返回完整的 <html><body> 文档但省略 <head>。不能再额外包一层 html，
  // 否则浏览器会将 CSP meta 重排到 body 并忽略，从而破坏隔离页面的资源约束。
  if (/<html[\s>]/iu.test(documentContent)) return documentContent.replace(/<html([^>]*)>/iu, `<html$1><head>${policy}</head>`);
  return `<!doctype html><html><head>${policy}</head><body>${documentContent}</body></html>`;
}

/**
 * HTML 与 Slides 只允许在无同源权限的 sandbox iframe 中预览。
 * iframe 与主应用隔离；CSP 仅允许 master Report 实际需要的 unpkg/jsDelivr 静态样式与 ECharts 脚本，
 * 仍禁止连接接口、表单、对象和导航，Slides 的本地脚本可在沙箱内完成翻页。
 */
export function SandboxedHtmlArtifactRenderer({ preview }: ArtifactRendererProps) {
  const content = preview.kind === 'text' ? preview.content : '';
  return <iframe
    className={styles.frame}
    title="隔离的 HTML Artifact 预览"
    sandbox="allow-scripts"
    referrerPolicy="no-referrer"
    srcDoc={isolatedDocument(content)}
  />;
}
