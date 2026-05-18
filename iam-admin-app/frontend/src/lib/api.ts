/**
 * Returns an absolute path for a URL relative to the application base path.
 *
 * The base is read from document.baseURI at runtime, which reflects the
 * <base href="..."> tag injected by the backend. This means a single built
 * artifact works under any server.servlet.context-path without rebuilding.
 *
 * Usage:
 *   fetch(apiUrl('api/me'))           → /api/me  or  /admin/api/me
 *   window.location.href = apiUrl('') → /         or  /admin/
 */
export function apiUrl(path: string): string {
  const p = path.startsWith('/') ? path.slice(1) : path;
  const url = new URL(p, document.baseURI);
  return url.pathname + url.search;
}

/**
 * Wrapper around fetch that intercepts 401 Unauthorized responses.
 *
 * When the server-side session has expired, the backend returns 401 for API
 * requests instead of redirecting (which fetch would follow silently). This
 * wrapper detects that case, redirects the browser to the login page with a
 * ?sessionExpired flag, and returns a promise that never resolves so callers
 * do not process a stale response.
 */
export async function apiFetch(input: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(input, init);
  if (response.status === 401) {
    window.location.href = apiUrl('') + '?sessionExpired';
    return new Promise(() => {});
  }
  return response;
}
