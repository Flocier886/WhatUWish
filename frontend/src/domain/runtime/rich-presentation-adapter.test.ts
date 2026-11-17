import { describe, expect, it } from 'vitest';
import { projectRichPresentation } from './rich-presentation-adapter';
import type { ToolProgressPayload } from './types';

const imageProgress = (presentationData: Record<string, unknown>): ToolProgressPayload => ({
  runtimeEventId: 'image-stage-1',
  toolInvocationId: 'image-tool-1',
  toolName: 'image_generation_tool',
  stage: 'artifact_created',
  status: 'running',
  message: '第 1 张图片已登记。',
  presentationKind: 'image.progress.v1',
  presentationSchemaVersion: '1',
  presentationData,
});

describe('rich image presentation adapter', () => {
  it('仅投影 Image 稳定阶段白名单字段', () => {
    const projection = projectRichPresentation(imageProgress({
      mode: 'edits', size: '1024x1024', referenceCount: 2, maskCount: 1, generatedCount: 1,
      fileName: 'generated.png', artifactId: 'artifact-image-1', usedFallback: true,
      prompt: '不得进入 UI 状态', storageKey: 'private/key', url: 'https://upstream.example/image', base64: 'data:image/png;base64,secret',
    }));

    expect(projection.slotKey).toBe('image-progress');
    expect(projection.view.label).toBe('图片已登记');
    expect(JSON.stringify(projection.view)).toContain('generated.png');
    expect(JSON.stringify(projection.view)).not.toContain('不得进入 UI 状态');
    expect(JSON.stringify(projection.view)).not.toContain('private/key');
    expect(JSON.stringify(projection.view)).not.toContain('upstream.example');
    expect(JSON.stringify(projection.view)).not.toContain('base64');
  });

  it('未知图片协议版本 fail closed', () => {
    const projection = projectRichPresentation({ ...imageProgress({}), presentationSchemaVersion: '2' });
    expect(projection.view.unavailable).toBe(true);
  });
});

describe('code interpreter step presentation', () => {
  it('shows code and observation from the versioned contract only', () => {
    const projection = projectRichPresentation({ ...imageProgress({
      step: 2, thought: '修正统计', code: 'print(5)', observation: '5', storageKey: 'private/key',
    }), toolName: 'code_interpreter', stage: 'step', message: '代码分析步骤 2',
    presentationKind: 'code_interpreter.step.v1' });
    expect(projection.view.label).toBe('执行步骤 2');
    expect(projection.view.details.find((item) => item.label === '代码')?.value).toBe('print(5)');
    expect(projection.view.details.find((item) => item.label === '执行观察')?.value).toBe('5');
    expect(JSON.stringify(projection.view)).not.toContain('private/key');
  });
});
