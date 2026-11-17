import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import type { RuntimeDataSource } from '@/gateways/RuntimeDataSource';
import { RuntimeProvider } from '@/app/RuntimeProvider';

export function renderWithRuntime(ui: ReactElement, dataSource?: RuntimeDataSource) {
  return render(<RuntimeProvider dataSource={dataSource}>{ui}</RuntimeProvider>);
}
