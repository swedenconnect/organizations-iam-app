/**
 * Organization Service
 * Handles all organization-related data operations.
 */

import type { Organization } from '@/types';
import { loadFromStorage, saveToStorage, STORAGE_KEYS } from './storageService';
import { apiUrl } from '@/lib/api';

// Mock initial data
const INITIAL_ORGANIZATIONS: Organization[] = [
  {
    id: '1',
    nameSv: 'Litsec AB',
    nameEn: 'Litsec AB',
    organizationNumber: '556677-8899',
    contactEmail: 'contact@litsec.se',
  },
];

/**
 * Get all organizations
 */
export async function getOrganizations(): Promise<Organization[]> {
  // TODO: Replace with fetch('/api/organizations')
  return loadFromStorage(STORAGE_KEYS.ORGANIZATIONS, INITIAL_ORGANIZATIONS);
}

/**
 * Create a new organization
 */
export async function createOrganization(org: Omit<Organization, 'id'>): Promise<Organization> {
  const response = await fetch(apiUrl('api/organizations'), {
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

  const response = await fetch(apiUrl(`api/organizations/${id}`), {
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
  const response = await fetch(apiUrl(`api/organizations/${id}`), { method: 'DELETE' });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) throw new Error('HAS_ATTACHED_FUNCTIONS');
  if (response.status === 404) throw new Error('NOT_FOUND');
  if (!response.ok) throw new Error(`Delete organization failed: ${response.status}`);
}

/**
 * Get a single organization by ID
 */
export async function getOrganizationById(id: string): Promise<Organization | null> {
  const organizations = await getOrganizations();
  // TODO: Replace with fetch(`/api/organizations/${id}`)
  return organizations.find((o) => o.id === id) || null;
}

/**
 * Save all organizations (used for batch updates from localStorage)
 */
export async function saveOrganizations(organizations: Organization[]): Promise<void> {
  await saveToStorage(STORAGE_KEYS.ORGANIZATIONS, organizations);
  // TODO: Replace with fetch('/api/organizations/batch', { method: 'POST', body: JSON.stringify(organizations) })
}