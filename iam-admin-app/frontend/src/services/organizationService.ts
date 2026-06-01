/**
 * Organization Service
 * Handles all organization-related data operations.
 */

import type { Organization, OrganizationPage } from '@/types';
import { saveToStorage, STORAGE_KEYS } from './storageService';
import { apiUrl, apiFetch } from '@/lib/api';

export const DEFAULT_ORG_PAGE_SIZE = 20;

/** Batch size used internally by fetchAllOrganizations — large enough to fetch everything in one round-trip for typical deployments. */
const FETCH_ALL_BATCH_SIZE = 500;

/**
 * Get a page of organizations from the backend.
 */
export async function getOrganizations(page = 0, size = DEFAULT_ORG_PAGE_SIZE, search = ''): Promise<OrganizationPage> {
  const searchParam = search.trim() ? `&search=${encodeURIComponent(search.trim())}` : '';
  const response = await apiFetch(apiUrl(`api/organizations?page=${page}&size=${size}${searchParam}`));
  if (!response.ok) {
    throw new Error(`Failed to fetch organizations: ${response.status}`);
  }
  return response.json();
}

/**
 * Fetch all organizations by paging through the backend until all pages are loaded.
 * Uses a large batch size to minimise the number of HTTP round-trips.
 */
export async function fetchAllOrganizations(): Promise<OrganizationPage> {
  const first = await getOrganizations(0, FETCH_ALL_BATCH_SIZE);
  if (first.totalPages <= 1) {
    return first;
  }
  const rest = await Promise.all(
    Array.from({ length: first.totalPages - 1 }, (_, i) => getOrganizations(i + 1, FETCH_ALL_BATCH_SIZE))
  );
  return {
    ...first,
    content: [first, ...rest].flatMap((p) => p.content),
  };
}

/**
 * Create a new organization
 */
export async function createOrganization(org: Omit<Organization, 'id'>): Promise<Organization> {
  const response = await apiFetch(apiUrl('api/organizations'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      organizationNumber: org.organizationNumber,
      nameSv: org.nameSv,
      nameEn: org.nameEn,
    }),
  });
  if (response.status === 409) {
    throw new Error('DUPLICATE_ORG_NUMBER');
  }
  if (!response.ok) {
    throw new Error(`Create organization failed: ${response.status}`);
  }
  return response.json();
}

/**
 * Update an existing organization
 */
export async function updateOrganization(id: string, org: Partial<Organization>): Promise<Organization> {
  const body: Record<string, unknown> = {
    contactEmail: org.contactEmail ?? null,
    contactPhone: org.additionalData?.contactPhone ?? null,
  };
  if (org.nameSv != null) body.nameSv = org.nameSv;
  if (org.nameEn != null) body.nameEn = org.nameEn;

  const response = await apiFetch(apiUrl(`api/organizations/${id}`), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (!response.ok) throw new Error(`Failed to update organization: ${response.status}`);
  const data = await response.json();
  return {
    id: data.orgIdentifier,
    organizationNumber: data.orgIdentifier,
    nameSv: data.nameSv ?? '',
    nameEn: data.nameEn ?? '',
    contactEmail: data.contactEmail ?? undefined,
    additionalData: data.contactPhone
      ? { contactPhone: data.contactPhone }
      : undefined,
  };
}

/**
 * Delete an organization
 */
export async function deleteOrganization(id: string): Promise<void> {
  const response = await apiFetch(apiUrl(`api/organizations/${id}`), { method: 'DELETE' });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) throw new Error('HAS_ATTACHED_FUNCTIONS');
  if (response.status === 404) throw new Error('NOT_FOUND');
  if (!response.ok) throw new Error(`Delete organization failed: ${response.status}`);
}

/**
 * Get a single organization by ID
 */
export async function getOrganizationById(id: string): Promise<Organization | null> {
  const response = await apiFetch(apiUrl(`api/organizations/${id}`));
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Failed to fetch organization: ${response.status}`);
  const o = await response.json();
  return {
    id: o.orgIdentifier,
    organizationNumber: o.orgIdentifier,
    nameSv: o.nameSv ?? '',
    nameEn: o.nameEn ?? '',
    contactEmail: o.contactEmail ?? undefined,
    additionalData: o.contactPhone ? { contactPhone: o.contactPhone } : undefined,
  };
}

/**
 * Save all organizations (used for batch updates from localStorage)
 */
export async function saveOrganizations(organizations: Organization[]): Promise<void> {
  await saveToStorage(STORAGE_KEYS.ORGANIZATIONS, organizations);
  // TODO: Replace with fetch('/api/organizations/batch', { method: 'POST', body: JSON.stringify(organizations) })
}