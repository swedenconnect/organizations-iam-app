/**
 * Function Service
 * Handles all function-related data operations (functions and organization-function assignments).
 */

import type { FunctionData, FunctionType, OrganizationFunction } from '@/types';
import { loadFromStorage, saveToStorage, STORAGE_KEYS } from './storageService';
import { apiUrl, apiFetch } from '@/lib/api';

// Mock initial data (empty for org-functions)
const INITIAL_ORG_FUNCTIONS: OrganizationFunction[] = [];

function toFunctionType(data: FunctionData): FunctionType {
  return {
    id: data.id,
    name: data.id,
    nameSv: data.nameSv ?? '',
    nameEn: data.nameEn ?? '',
    descriptionSv: data.descriptionSv ?? '',
    descriptionEn: data.descriptionEn ?? '',
  };
}

/**
 * Get all functions
 */
export async function getFunctions(): Promise<FunctionType[]> {
  const response = await apiFetch(apiUrl('api/functions'));
  if (!response.ok) throw new Error('FETCH_FUNCTIONS_FAILED');
  const data: FunctionData[] = await response.json();
  return data.map(toFunctionType);
}

/**
 * Create a new function
 */
export async function createFunction(func: Omit<FunctionType, 'id'>): Promise<FunctionType> {
  const response = await apiFetch(apiUrl('api/functions'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: func.name,
      nameSv: func.nameSv,
      nameEn: func.nameEn,
      descriptionSv: func.descriptionSv || undefined,
      descriptionEn: func.descriptionEn || undefined,
    }),
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) throw new Error('DUPLICATE_FUNCTION_NAME');
  if (!response.ok) throw new Error('CREATE_FUNCTION_FAILED');
  const data: FunctionData = await response.json();
  return toFunctionType(data);
}

/**
 * Update an existing function
 */
export async function updateFunction(id: string, func: Partial<FunctionType>): Promise<FunctionType> {
  const response = await apiFetch(apiUrl(`api/functions/${id}`), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      nameSv: func.nameSv ?? '',
      nameEn: func.nameEn ?? '',
      descriptionSv: func.descriptionSv || undefined,
      descriptionEn: func.descriptionEn || undefined,
    }),
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 404) throw new Error('NOT_FOUND');
  if (!response.ok) throw new Error('UPDATE_FUNCTION_FAILED');
  const data: FunctionData = await response.json();
  return toFunctionType(data);
}

/**
 * Delete a function
 */
export async function deleteFunction(id: string): Promise<void> {
  const response = await apiFetch(apiUrl(`api/functions/${id}`), {
    method: 'DELETE',
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 404) throw new Error('NOT_FOUND');
  if (!response.ok) throw new Error('DELETE_FUNCTION_FAILED');
}

/**
 * Get a single function by ID
 */
export async function getFunctionById(id: string): Promise<FunctionType | null> {
  const functions = await getFunctions();
  // TODO: Replace with fetch(`/api/functions/${id}`)
  return functions.find((f) => f.id === id) || null;
}

/**
 * Save all functions (used for batch updates from localStorage)
 */
export async function saveFunctions(functions: FunctionType[]): Promise<void> {
  await saveToStorage(STORAGE_KEYS.FUNCTIONS, functions);
  // TODO: Replace with fetch('/api/functions/batch', { method: 'POST', body: JSON.stringify(functions) })
}

/**
 * Get all organization-function assignments
 */
export async function getOrganizationFunctions(): Promise<OrganizationFunction[]> {
  // TODO: Replace with fetch('/api/organization-functions')
  return loadFromStorage(STORAGE_KEYS.ORG_FUNCTIONS, INITIAL_ORG_FUNCTIONS);
}

/**
 * Assign functions to an organization (add-only — calls one endpoint per function)
 */
export async function assignFunctionsToOrganization(
  organizationId: string,
  functionIds: string[]
): Promise<OrganizationFunction[]> {
  const results: OrganizationFunction[] = [];
  for (const functionId of functionIds) {
    const response = await apiFetch(
      apiUrl(`api/organizations/${organizationId}/functions/${functionId}`),
      { method: 'POST' }
    );
    if (response.status === 403) throw new Error('FORBIDDEN');
    if (!response.ok) throw new Error('ASSIGN_FUNCTION_FAILED');
    results.push({ id: `${organizationId}:${functionId}`, organizationId, functionId });
  }
  return results;
}

/**
 * Remove all function assignments for a specific organization (used when deleting an organization)
 */
export async function removeOrganizationFunctions(organizationId: string, functionIds: string[]): Promise<void> {
  for (const functionId of functionIds) {
    const response = await apiFetch(
      apiUrl(`api/organizations/${organizationId}/functions/${functionId}`),
      { method: 'DELETE' }
    );
    if (!response.ok && response.status !== 404) {
      throw new Error(`Failed to detach function ${functionId}: ${response.status}`);
    }
  }
}

/**
 * Detach a single function from an organization
 */
export async function detachFunctionFromOrganization(organizationId: string, functionId: string): Promise<void> {
  const response = await apiFetch(
    apiUrl(`api/organizations/${organizationId}/functions/${functionId}`),
    { method: 'DELETE' }
  );
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 404) throw new Error('NOT_FOUND');
  if (!response.ok) throw new Error(`Detach function failed: ${response.status}`);
}

/**
 * Remove all organization assignments for a specific function (used when deleting a function)
 */
export async function removeFunctionFromOrganizations(functionId: string): Promise<void> {
  const orgFunctions = await getOrganizationFunctions();
  const updatedOrgFunctions = orgFunctions.filter((of) => of.functionId !== functionId);
  await saveToStorage(STORAGE_KEYS.ORG_FUNCTIONS, updatedOrgFunctions);
  
  // TODO: Replace with fetch(`/api/functions/${functionId}/organizations`, { method: 'DELETE' })
}

/**
 * Save all organization-function assignments (used for batch updates from localStorage)
 */
export async function saveOrganizationFunctions(orgFunctions: OrganizationFunction[]): Promise<void> {
  await saveToStorage(STORAGE_KEYS.ORG_FUNCTIONS, orgFunctions);
  // TODO: Replace with fetch('/api/organization-functions/batch', { method: 'POST', body: JSON.stringify(orgFunctions) })
}