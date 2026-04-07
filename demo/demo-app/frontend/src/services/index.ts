import { CurrentUser, ContactData, OrgEntry } from '@/types';

async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
  const resp = await fetch(url, { ...options, credentials: 'include' });
  if (resp.status === 401) {
    try {
      const body = await resp.json();
      if (body.authorizationUrl) {
        window.location.href = body.authorizationUrl;
        return new Promise(() => {});
      }
    } catch {
      // not JSON — fall through
    }
  }
  return resp;
}

export async function fetchMe(): Promise<CurrentUser | null> {
  const resp = await apiFetch('/api/me');
  if (resp.status === 401) {
    return null;
  }
  if (!resp.ok) {
    throw new Error(`fetchMe failed: ${resp.status}`);
  }
  return resp.json();
}

export async function fetchContactData(orgId: string): Promise<ContactData> {
  const resp = await apiFetch(`/api/demo/${encodeURIComponent(orgId)}/contact`);
  if (!resp.ok) throw new Error(`GET contact failed: ${resp.status}`);
  return resp.json();
}

export async function saveContactData(orgId: string, data: ContactData): Promise<void> {
  const resp = await apiFetch(`/api/demo/${encodeURIComponent(orgId)}/contact`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (resp.status === 401) {
    // apiFetch already navigated — this code is unreachable in practice,
    // but guard against race conditions.
    return;
  }
  if (!resp.ok) throw new Error(`PUT contact failed: ${resp.status}`);
}

export async function fetchAdminUrl(orgId: string): Promise<string> {
  const response = await fetch(`/api/demo/${encodeURIComponent(orgId)}/admin-url`, {
    credentials: 'include',
  });
  if (!response.ok) {
    throw new Error(`fetchAdminUrl failed: ${response.status}`);
  }
  const json: { url: string } = await response.json();
  return json.url;
}
