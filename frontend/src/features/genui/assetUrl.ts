/** 本站仅允许浏览器可请求的 URL；工作区相对路径须先经 Artifact 登记交付。 */
export function resolveGenUiAssetUrl(raw?: string | null, _requestId?: string | null): string {
  const value = (raw ?? '').trim();
  if (/^(https?:\/\/|blob:|data:image\/|\/api\/v1\/artifacts\/runs\/)/iu.test(value)) return value;
  return '';
}
