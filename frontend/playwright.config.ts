import { defineConfig, devices } from '@playwright/test';

const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
const browserOverride = executablePath ? { launchOptions: { executablePath } } : {};

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 8_000 },
  reporter: 'html',
  // 使用系统浏览器时串行运行，避免 Windows Chrome 在并发启动时过早退出。
  workers: executablePath ? 1 : undefined,
  use: { baseURL: 'http://127.0.0.1:5173', trace: 'on-first-retry' },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], ...browserOverride, viewport: { width: 1440, height: 900 } } },
    // 以窄视口和 touch 语义验证响应式 UI，避免要求系统安装特定的移动设备浏览器。
    { name: 'mobile', use: { ...browserOverride, viewport: { width: 390, height: 844 }, hasTouch: true } },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
  },
});
