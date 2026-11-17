import { render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { SandboxedHtmlArtifactRenderer } from './SandboxedHtmlArtifactRenderer';

describe('SandboxedHtmlArtifactRenderer', () => {
  it('在无同源权限、受控静态资源白名单的 iframe 中隔离预览 HTML', () => {
    render(<SandboxedHtmlArtifactRenderer
      preview={{ kind: 'text', content: '<html><head></head><body><script>window.top.location = "https://bad.example"</script><h1>报告</h1></body></html>' }}
      scrollRootRef={createRef<HTMLDivElement>()}
    />);

    const frame = screen.getByTitle('隔离的 HTML Artifact 预览');
    expect(frame).toHaveAttribute('sandbox', 'allow-scripts');
    expect(frame).toHaveAttribute('referrerpolicy', 'no-referrer');
    expect(frame).not.toHaveAttribute('sandbox', expect.stringContaining('allow-same-origin'));
    expect(frame).toHaveAttribute('srcdoc', expect.stringContaining("default-src 'none'"));
    expect(frame).toHaveAttribute('srcdoc', expect.stringContaining("connect-src 'none'"));
    expect(frame).toHaveAttribute('srcdoc', expect.stringContaining('https://unpkg.com'));
    expect(frame).toHaveAttribute('srcdoc', expect.stringContaining('https://cdn.jsdelivr.net'));
    expect(frame).toHaveAttribute('srcdoc', expect.stringContaining('报告'));
  });

  it('为缺少 head 的完整 HTML 文档在原 html 根节点下补齐 CSP', () => {
    render(<SandboxedHtmlArtifactRenderer
      preview={{ kind: 'text', content: '<html><body><h1>无 Head 的报告</h1></body></html>' }}
      scrollRootRef={createRef<HTMLDivElement>()}
    />);

    const source = screen.getByTitle('隔离的 HTML Artifact 预览').getAttribute('srcdoc') ?? '';
    expect(source).toContain('<html><head>');
    expect(source).toContain("default-src 'none'");
    expect(source.match(/<html/gu)).toHaveLength(1);
  });

  it('丢弃模型误置于 HTML 文档前的文本，避免 CSP meta 落入 body', () => {
    render(<SandboxedHtmlArtifactRenderer
      preview={{ kind: 'text', content: '模型说明\n<!doctype html><html><head><title>报告</title></head><body>正文</body></html>' }}
      scrollRootRef={createRef<HTMLDivElement>()}
    />);

    const source = screen.getByTitle('隔离的 HTML Artifact 预览').getAttribute('srcdoc') ?? '';
    expect(source.startsWith('<!doctype html>')).toBe(true);
    expect(source).not.toContain('模型说明');
    expect(source).toContain("default-src 'none'");
  });
});
