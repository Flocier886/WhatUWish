import { Check, Copy, ImageOff } from 'lucide-react';
import { useEffect, useState, type MouseEvent, type ReactNode } from 'react';
import ReactMarkdown, { defaultUrlTransform, type Components } from 'react-markdown';
import rehypeSanitize from 'rehype-sanitize';
import rehypeSlug from 'rehype-slug';
import remarkGfm from 'remark-gfm';
import styles from './SafeMarkdown.module.css';

export type SafeMarkdownVariant = 'compact' | 'answer' | 'document';

interface SafeMarkdownProps {
  content: string;
  variant?: SafeMarkdownVariant;
  onFragmentNavigate?: (fragment: string) => void;
}

function textContent(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(textContent).join('');
  if (node && typeof node === 'object' && 'props' in node) return textContent((node as { props: { children?: ReactNode } }).props.children);
  return '';
}

function CopyablePre({ children }: { children?: ReactNode }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) return;
    const timeout = window.setTimeout(() => setCopied(false), 1600);
    return () => window.clearTimeout(timeout);
  }, [copied]);
  const copy = async () => {
    await navigator.clipboard.writeText(textContent(children).replace(/\n$/, ''));
    setCopied(true);
  };
  return <div className={styles.codeBlock}>
    <button type="button" className={styles.copyButton} aria-label={copied ? '代码已复制' : '复制代码'} title={copied ? '代码已复制' : '复制代码'} onClick={() => { void copy(); }}>
      {copied ? <Check size={16} /> : <Copy size={16} />}
    </button>
    <pre>{children}</pre>
  </div>;
}

function markdownComponents(variant: SafeMarkdownVariant, onFragmentNavigate?: (fragment: string) => void): Components {
  return {
    a: ({ href, children, ...props }) => {
      const fragment = href?.startsWith('#') ? href.slice(1) : undefined;
      const external = Boolean(href && /^https?:\/\//i.test(href));
      const handleClick = fragment && onFragmentNavigate
        ? (event: MouseEvent<HTMLAnchorElement>) => { event.preventDefault(); onFragmentNavigate(fragment); }
        : undefined;
      return <a {...props} href={href} onClick={handleClick} target={external ? '_blank' : undefined} rel={external ? 'noopener noreferrer' : undefined}>{children}</a>;
    },
    img: ({ src, alt }) => <span className={styles.blockedImage} role="note">
      <ImageOff size={16} aria-hidden="true" />
      <span>远程图片未自动加载{alt ? `：${alt}` : ''}</span>
      {src && /^https?:\/\//i.test(src) ? <a href={src} target="_blank" rel="noopener noreferrer">查看图片地址</a> : null}
    </span>,
    ...(variant === 'document' ? {
      pre: CopyablePre,
      table: ({ children, ...props }) => <div className={styles.tableScroll} role="region" aria-label="可横向滚动的表格" tabIndex={0}><table {...props}>{children}</table></div>,
    } satisfies Components : {}),
  };
}

export function SafeMarkdown({ content, variant = 'answer', onFragmentNavigate }: SafeMarkdownProps) {
  return <div className={`${styles.root} ${styles[variant]}`}>
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeSanitize, rehypeSlug]}
      components={markdownComponents(variant, onFragmentNavigate)}
      urlTransform={defaultUrlTransform}
    >
      {content}
    </ReactMarkdown>
  </div>;
}
