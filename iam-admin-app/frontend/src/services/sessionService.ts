import { AdminSessionData } from '@/types';

export async function fetchSessionData(): Promise<AdminSessionData | null> {
  const response = await fetch('/api/session');
  if (response.status === 204) return null;
  if (!response.ok) throw new Error(`Session fetch failed: ${response.status}`);
  return response.json() as Promise<AdminSessionData>;
}
