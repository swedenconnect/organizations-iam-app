/**
 * Client Service
 * Handles managed client administration. All endpoints are superuser-only.
 */

import type { ManagedClient, ManagedClientInput, ReconciliationReport } from '@/types';
import { apiUrl, apiFetch } from '@/lib/api';

function toBody(client: ManagedClientInput) {
  return {
    clientId: client.clientId,
    name: client.name || undefined,
    oidcClient: client.oidcClient,
    resourceServer: client.resourceServer,
    functions: client.functions,
    redirectUris: client.redirectUris,
    jwksUri: client.jwksUri || undefined,
    jwksString: client.jwksString || undefined,
  };
}

async function readError(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    return '';
  }
}

/**
 * Get everything the application administers — managed clients and resource servers.
 * Each entry carries a `type` discriminating the two.
 */
export async function getClients(): Promise<ManagedClient[]> {
  const response = await apiFetch(apiUrl('api/clients'));
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (!response.ok) throw new Error('FETCH_CLIENTS_FAILED');
  return response.json();
}

/**
 * Register a new managed client. The backend reconciles it before returning.
 */
export async function createClient(client: ManagedClientInput): Promise<ManagedClient> {
  const response = await apiFetch(apiUrl('api/clients'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(toBody(client)),
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) throw new Error('DUPLICATE_CLIENT_ID');
  if (response.status === 400) throw new Error(await readError(response));
  if (!response.ok) throw new Error('CREATE_CLIENT_FAILED');
  return response.json();
}

/**
 * Update a managed client. The client_id is immutable.
 *
 * Clients are addressed by their Keycloak UUID: a client_id is a URL, and a URL-encoded one
 * in a path segment is rejected by the backend's HTTP firewall before it reaches the API.
 */
export async function updateClient(
  id: string,
  client: ManagedClientInput
): Promise<ManagedClient> {
  const response = await apiFetch(apiUrl(`api/clients/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(toBody(client)),
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 404) throw new Error('NOT_FOUND');
  if (response.status === 400) throw new Error(await readError(response));
  if (!response.ok) throw new Error('UPDATE_CLIENT_FAILED');
  return response.json();
}

/**
 * Permanently delete a managed client from Keycloak
 */
export async function deleteClient(id: string): Promise<void> {
  const response = await apiFetch(apiUrl(`api/clients/${encodeURIComponent(id)}`), {
    method: 'DELETE',
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 404) throw new Error('NOT_FOUND');
  if (!response.ok) throw new Error('DELETE_CLIENT_FAILED');
}

/**
 * Reconcile a single client against the current org/function topology
 */
export async function reconcileClient(id: string): Promise<ReconciliationReport> {
  const response = await apiFetch(
    apiUrl(`api/clients/${encodeURIComponent(id)}/reconcile`),
    { method: 'POST' }
  );
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 404) throw new Error('NOT_FOUND');
  if (!response.ok) throw new Error('RECONCILE_FAILED');
  return response.json();
}

/**
 * Reconcile every managed client
 */
export async function reconcileAllClients(): Promise<ReconciliationReport> {
  const response = await apiFetch(apiUrl('api/clients/reconcile'), { method: 'POST' });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (!response.ok) throw new Error('RECONCILE_FAILED');
  return response.json();
}

