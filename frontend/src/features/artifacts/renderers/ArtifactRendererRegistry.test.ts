import { describe, expect, it } from 'vitest';
import { ArtifactRendererRegistry } from './ArtifactRendererRegistry';

describe('ArtifactRendererRegistry', () => {
  it('只精确匹配 kind 与 schemaVersion', () => {
    const registry = new ArtifactRendererRegistry<string>()
      .register({ kind: 'markdown', schemaVersion: 'v1' }, 'markdown-renderer');

    expect(registry.resolve({ kind: 'markdown', schemaVersion: 'v1' })).toBe('markdown-renderer');
    expect(registry.resolve({ kind: 'markdown', schemaVersion: 'v2' })).toBeUndefined();
    expect(registry.resolve({ kind: 'json', schemaVersion: 'v1' })).toBeUndefined();
    expect(registry.resolve()).toBeUndefined();
  });

  it('允许未来合同显式注册且不影响既有映射', () => {
    const registry = new ArtifactRendererRegistry<string>()
      .register({ kind: 'markdown', schemaVersion: 'v1' }, 'markdown-renderer')
      .register({ kind: 'future-json', schemaVersion: 'v7' }, 'future-renderer');

    expect(registry.resolve({ kind: 'future-json', schemaVersion: 'v7' })).toBe('future-renderer');
    expect(registry.resolve({ kind: 'markdown', schemaVersion: 'v1' })).toBe('markdown-renderer');
  });
});
