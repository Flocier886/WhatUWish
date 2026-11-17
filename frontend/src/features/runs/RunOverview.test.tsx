import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RunOverview } from './RunOverview';

describe('RunOverview', () => {
  it('显示当前 Run 状态与稳定 Run ID', () => {
    render(<RunOverview eventCount={3} run={{ runId: 'run-001', sessionId: 'session-001', requestId: 'request-001', agentId: 'agent-001', task: '整理反馈', status: 'RUNNING', startedAt: '2026-07-23T10:00:00Z' }} />);
    expect(screen.getByText('运行中')).toBeInTheDocument();
    expect(screen.getByText('run-001')).toBeInTheDocument();
    expect(screen.getByText('3 个活动步骤')).toBeInTheDocument();
  });
});
