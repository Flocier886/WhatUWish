import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { RuntimeOperation } from '@/domain/runtime/runtime-operation';
import { RuntimeOperationList } from './RuntimeOperationList';

const operations: RuntimeOperation[] = [
  { operationId: 'search', toolName: 'web_search', state: 'SUCCEEDED', lifecycleStartMissing: false, startedAt: '2026-11-11T00:00:00Z', updatedAt: '2026-11-11T00:00:02Z', argumentsSummary: '搜索最近的研究', observation: '找到了三个公开页面', stages: [], artifactIds: [], isRich: false },
  { operationId: 'research', toolName: 'deep_search_tool', state: 'SUCCEEDED', lifecycleStartMissing: false, startedAt: '2026-11-11T00:00:03Z', updatedAt: '2026-11-11T00:00:06Z', argumentsSummary: '整理证据', observation: '研究完成', artifactIds: [], isRich: true, stages: [{ stageId: 'source', slotKey: 'source', eventId: 'event-1', sequence: 1, occurredAt: '2026-11-11T00:00:04Z', status: 'completed', label: '搜索完成', summary: '1 条来源', details: [{ label: '公开文档', value: 'example.com', href: 'https://example.com/docs', description: '原始文档' }] }] },
];

describe('RuntimeOperationList', () => {
  it('普通与 Rich 工具均可展开详情，Rich 来源链接可访问', async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(<RuntimeOperationList runId="run-1" operations={operations} onSelect={onSelect} />);
    await user.click(screen.getByRole('button', { name: /搜索网页.*搜索最近的研究/ }));
    expect(screen.getByText('找到了三个公开页面')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /深度研究.*整理证据/ }));
    await user.click(screen.getByRole('button', { name: /搜索完成.*1 条来源/ }));
    expect(screen.getByRole('link', { name: '打开来源 公开文档' })).toHaveAttribute('href', 'https://example.com/docs');
    await user.click(screen.getByRole('button', { name: '在工作区查看 深度研究' }));
    expect(onSelect).toHaveBeenCalledWith({ kind: 'operation', runId: 'run-1', operationId: 'research' });
  });
});
