/**
 * User Service
 * Handles all user-related data operations.
 */

import type { User, UserOrganizationRole } from '@/types';
import { loadFromStorage, saveToStorage, STORAGE_KEYS } from './storageService';
import { apiUrl, apiFetch } from '@/lib/api';

// Mock initial data
const INITIAL_USERS: User[] = [
  {
    id: '1',
    name: 'Martin Lindström',
    email: 'martin@litsec.se',
    personalIdentityNumber: '196911292032',
    phoneNumber: '+46701234567',
  },
];

/**
 * Get all users
 */
export async function getUsers(): Promise<User[]> {
  // TODO: Replace with fetch('/api/users')
  return loadFromStorage(STORAGE_KEYS.USERS, INITIAL_USERS);
}

/**
 * Thrown when removing or changing an admin right would leave an organization or
 * function without any administrator.
 */
export class LastAdminError extends Error {
  constructor(public readonly scope: string) {
    super('LAST_ADMIN');
    this.name = 'LastAdminError';
  }
}

/**
 * Thrown when POST /api/users returns 409 because the personal identity number is already
 * registered. The existing user's Keycloak ID is included when the backend provides it.
 */
export class DuplicatePinError extends Error {
  constructor(public readonly existingUserId: string | undefined) {
    super('DUPLICATE_PERSONAL_IDENTITY_NUMBER');
    this.name = 'DuplicatePinError';
  }
}

/**
 * Create a new user
 */
export async function createUser(user: Omit<User, 'id'>): Promise<User> {
  const response = await apiFetch(apiUrl('api/users'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: user.name,
      email: user.email,
      personalIdentityNumber: user.personalIdentityNumber.replace(/-/g, ''),
      phoneNumber: user.phoneNumber ?? null,
    }),
  });

  if (response.status === 409) {
    const body = await response.json().catch(() => ({}));
    const existingUserId: string | undefined = body?.existingUserId;
    throw new DuplicatePinError(existingUserId);
  }
  if (!response.ok) {
    throw new Error(`Failed to create user: ${response.status}`);
  }

  const data = await response.json();
  return {
    id: data.id,
    name: data.name,
    email: data.email ?? '',
    personalIdentityNumber: data.personalIdentityNumber ?? '',
    phoneNumber: data.phoneNumber ?? undefined,
    superuser: false,
    rights: [],
  };
}

/**
 * Update an existing user
 */
export async function updateUser(id: string, user: Partial<User>): Promise<User> {
  const response = await apiFetch(apiUrl(`api/users/${id}`), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: user.name,
      email: user.email,
      phoneNumber: user.phoneNumber ?? null,
    }),
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (!response.ok) throw new Error(`Failed to update user: ${response.status}`);
  const data = await response.json();
  return {
    id: data.id,
    name: data.name,
    email: data.email ?? '',
    personalIdentityNumber: data.personalIdentityNumber ?? '',
    phoneNumber: data.phoneNumber || undefined,
    superuser: user.superuser ?? false,
    rights: user.rights ?? [],
  };
}

/**
 * Delete a user
 */
export async function deleteUser(id: string): Promise<void> {
  const response = await apiFetch(apiUrl(`api/users/${id}`), { method: 'DELETE' });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (!response.ok) throw new Error(`Failed to delete user: ${response.status}`);
}

/**
 * Get a single user by ID
 */
export async function getUserById(id: string): Promise<User | null> {
  const response = await apiFetch(apiUrl(`api/users/${id}`));
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Failed to fetch user: ${response.status}`);
  const data = await response.json();
  return {
    id: data.id,
    name: data.name,
    email: data.email ?? '',
    personalIdentityNumber: data.personalIdentityNumber ?? '',
    phoneNumber: data.phoneNumber || undefined,
    superuser: false,
    rights: [],
  };
}

/**
 * Save all users (used for batch updates from localStorage)
 */
export async function saveUsers(users: User[]): Promise<void> {
  await saveToStorage(STORAGE_KEYS.USERS, users);
  // TODO: Replace with fetch('/api/users/batch', { method: 'POST', body: JSON.stringify(users) })
}

/**
 * Get all user roles
 */
export async function getUserRoles(): Promise<UserOrganizationRole[]> {
  return [];
}

/**
 * Add a user to an organization with a specific role
 */
export async function addUserToOrganization(
  organizationId: string,
  userId: string,
  role: 'read' | 'write' | 'admin'
): Promise<UserOrganizationRole> {
  const response = await apiFetch(
    apiUrl(`api/organizations/${organizationId}/users/${userId}/rights`),
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ right: role }),
    }
  );
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) {
    const body = await response.json().catch(() => ({}));
    throw new LastAdminError(body?.scope ?? organizationId);
  }
  if (!response.ok) throw new Error(`Failed to add user to org: ${response.status}`);
  return {
    id: `${userId}:${organizationId}:*`,
    userId,
    organizationId,
    role,
  };
}

/**
 * Remove a user from an organization (organization-level access only)
 */
export async function removeUserFromOrganization(
  organizationId: string,
  userId: string,
  right: 'read' | 'write' | 'admin'
): Promise<void> {
  const response = await apiFetch(
    apiUrl(`api/organizations/${organizationId}/users/${userId}/rights?right=${right}`),
    { method: 'DELETE' }
  );
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) {
    const body = await response.json().catch(() => ({}));
    throw new LastAdminError(body?.scope ?? organizationId);
  }
  if (!response.ok) throw new Error(`Failed to remove user from org: ${response.status}`);
}

/**
 * Add a user to a specific function within an organization
 */
export async function addUserToFunction(
  organizationId: string,
  functionId: string,
  userId: string,
  role: 'read' | 'write' | 'admin'
): Promise<UserOrganizationRole> {
  const response = await apiFetch(
    apiUrl(`api/organizations/${organizationId}/functions/${functionId}/users/${userId}/rights`),
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ right: role }),
    }
  );
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) {
    const body = await response.json().catch(() => ({}));
    throw new LastAdminError(body?.scope ?? `${organizationId}/${functionId}`);
  }
  if (!response.ok) throw new Error(`Failed to add user to function: ${response.status}`);
  return {
    id: `${userId}:${organizationId}:${functionId}`,
    userId,
    organizationId,
    functionId,
    role,
  };
}

/**
 * Remove a user from a specific function
 */
export async function removeUserFromFunction(
  organizationId: string,
  functionId: string,
  userId: string,
  right: 'read' | 'write' | 'admin'
): Promise<void> {
  const response = await apiFetch(
    apiUrl(`api/organizations/${organizationId}/functions/${functionId}/users/${userId}/rights?right=${right}`),
    { method: 'DELETE' }
  );
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) {
    const body = await response.json().catch(() => ({}));
    throw new LastAdminError(body?.scope ?? `${organizationId}/${functionId}`);
  }
  if (!response.ok) throw new Error(`Failed to remove user from function: ${response.status}`);
}

/**
 * Remove all roles for a specific user (used when deleting a user)
 */
export async function removeAllUserRoles(userId: string): Promise<void> {
  // TODO: Replace with fetch(`/api/users/${userId}/roles`, { method: 'DELETE' })
}

/**
 * Remove all user roles for a specific organization (used when deleting an organization)
 */
export async function removeAllOrganizationRoles(organizationId: string): Promise<void> {
  // TODO: Replace with fetch(`/api/organizations/${organizationId}/roles`, { method: 'DELETE' })
}
