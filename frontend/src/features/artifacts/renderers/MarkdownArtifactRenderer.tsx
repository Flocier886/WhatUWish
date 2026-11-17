import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { SafeMarkdown } from '@/components/shared/SafeMarkdown';
import styles from './MarkdownArtifactRenderer.module.css';
import type { ArtifactPreviewValue } from '../useArtifactPreview';

export interface ArtifactRendererProps {
  preview: ArtifactPreviewValue;
  scrollRootRef: RefObject<HTMLDivElement | null>;
  downloadHref?: string;
}

interface HeadingEntry {
  id: string;
  label: string;
  level: number;
  element: HTMLHeadingElement;
}

function decodeFragment(fragment: string): string {
  try { return decodeURIComponent(fragment); } catch { return fragment; }
}

function scrollHeading(root: HTMLDivElement, heading: HTMLHeadingElement): void {
  const top = heading.offsetTop - 18;
  const behavior = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth';
  if (typeof root.scrollTo === 'function') root.scrollTo({ top, behavior });
  else root.scrollTop = top;
}

export function MarkdownArtifactRenderer({ preview, scrollRootRef }: ArtifactRendererProps) {
  const content = preview.kind === 'text' ? preview.content : '';
  const documentRef = useRef<HTMLDivElement>(null);
  const [headings, setHeadings] = useState<HeadingEntry[]>([]);
  const [activeId, setActiveId] = useState<string>();
  const [mobileTocOpen, setMobileTocOpen] = useState(false);

  useLayoutEffect(() => {
    const entries = Array.from(documentRef.current?.querySelectorAll<HTMLHeadingElement>('h1[id], h2[id], h3[id]') ?? []).map((element) => ({
      id: element.id,
      label: element.textContent?.trim() || element.id,
      level: Number(element.tagName.slice(1)),
      element,
    }));
    setHeadings(entries);
    setActiveId(entries[0]?.id);
  }, [content]);

  useEffect(() => {
    const root = scrollRootRef.current;
    if (!root || headings.length === 0 || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting).sort((left, right) => left.boundingClientRect.top - right.boundingClientRect.top)[0];
      if (visible?.target.id) setActiveId(visible.target.id);
    }, { root, rootMargin: '-12% 0px -72% 0px', threshold: [0, 1] });
    headings.forEach(({ element }) => observer.observe(element));
    return () => observer.disconnect();
  }, [headings, scrollRootRef]);

  const navigate = (fragment: string) => {
    const id = decodeFragment(fragment);
    const target = headings.find((heading) => heading.id === id)?.element
      ?? Array.from(documentRef.current?.querySelectorAll<HTMLElement>('[id]') ?? []).find((element) => element.id === id);
    const root = scrollRootRef.current;
    if (!root || !target) return;
    scrollHeading(root, target as HTMLHeadingElement);
    setActiveId(id);
  };

  return <div className={styles.layout}>
    {headings.length >= 3 && <aside className={styles.toc}>
      <button type="button" className={styles.tocToggle} aria-expanded={mobileTocOpen} onClick={() => setMobileTocOpen((current) => !current)}>
        {mobileTocOpen ? '收起文档目录' : '打开文档目录'}
      </button>
      <nav className={`${styles.tocNav} ${mobileTocOpen ? styles.tocNavOpen : ''}`} aria-label="文档目录">
        {headings.map((heading) => <button
          type="button"
          key={heading.id}
          className={`${styles.tocItem} ${activeId === heading.id ? styles.tocItemActive : ''}`}
          aria-current={activeId === heading.id ? 'location' : undefined}
          style={{ paddingLeft: `${10 + (heading.level - 1) * 12}px` }}
          onClick={() => navigate(heading.id)}
        >{heading.label}</button>)}
      </nav>
    </aside>}
    <div ref={documentRef} className={styles.document}>
      <SafeMarkdown content={content} variant="document" onFragmentNavigate={navigate} />
    </div>
  </div>;
}
