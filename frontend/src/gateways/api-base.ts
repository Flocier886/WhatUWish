export function apiUrl(path: string): string {
  const configuredBaseUrl = import.meta.env.VITE_AGENT_API_BASE_URL;
  const baseUrl = (configuredBaseUrl ?? (import.meta.env.DEV ? 'http://127.0.0.1:8091' : '')).replace(/\/$/, '');
  return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
}
