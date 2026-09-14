/**
 * Import/Export Service
 * Bulk export/import of organizations, functions and users. All endpoints are superuser-only.
 */

import type { ImportExportBundle, ImportPreviewReport, ImportReport } from '@/types';
import { apiUrl, apiFetch } from '@/lib/api';

async function readError(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    return '';
  }
}

/**
 * Fetch the full-realm export bundle (functions, organizations, users with rights).
 * Superuser accounts are excluded by the backend.
 */
export async function exportBundle(): Promise<ImportExportBundle> {
  const response = await apiFetch(apiUrl('api/export'));
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (!response.ok) throw new Error('EXPORT_FAILED');
  return response.json();
}

/**
 * Triggers a browser download of the current export bundle as a JSON file.
 */
export async function downloadExport(filename = 'iam-export.json'): Promise<void> {
  const bundle = await exportBundle();
  const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
  } finally {
    URL.revokeObjectURL(url);
  }
}

/**
 * Validates an uploaded import file against the current realm state without creating
 * anything. The resulting batch is held server-side (in the session) under the returned
 * batchId for a subsequent confirmImport call.
 */
export async function dryRunImport(file: File): Promise<ImportPreviewReport> {
  const formData = new FormData();
  formData.append('file', file);
  // No Content-Type header: the browser sets the multipart boundary itself.
  const response = await apiFetch(apiUrl('api/import/dry-run'), {
    method: 'POST',
    body: formData,
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 400) throw new Error((await readError(response)) || 'INVALID_FILE');
  if (!response.ok) throw new Error('DRY_RUN_FAILED');
  return response.json();
}

/**
 * Creates every entry in the pending batch identified by batchId.
 */
export async function confirmImport(batchId: string): Promise<ImportReport> {
  const response = await apiFetch(apiUrl(`api/import/${encodeURIComponent(batchId)}/confirm`), {
    method: 'POST',
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (response.status === 409) throw new Error('BATCH_NOT_FOUND');
  if (!response.ok) throw new Error('CONFIRM_FAILED');
  return response.json();
}

/**
 * Discards a pending batch without creating anything.
 */
export async function cancelImport(batchId: string): Promise<void> {
  const response = await apiFetch(apiUrl(`api/import/${encodeURIComponent(batchId)}`), {
    method: 'DELETE',
  });
  if (response.status === 403) throw new Error('FORBIDDEN');
  if (!response.ok && response.status !== 404) throw new Error('CANCEL_FAILED');
}
