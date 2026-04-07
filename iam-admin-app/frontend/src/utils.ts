import { UserOrgRight } from '@/types';

/**
 * Formats an organization number as NNNNNN-NNNN (dash before the last four digits).
 * Handles values that are already formatted or shorter than four digits.
 */
export function formatOrgNumber(value: string): string {
  const digits = value.replace(/-/g, '');
  return digits.length > 4 ? `${digits.slice(0, -4)}-${digits.slice(-4)}` : value;
}

/**
 * Formats a personal identity number as NNNNNN-NNNN (dash before the last four digits).
 * Handles values that are already formatted or shorter than four digits.
 */
export function formatPersonalIdentityNumber(value: string): string {
  const digits = value.replace(/-/g, '');
  return digits.length > 4 ? `${digits.slice(0, -4)}-${digits.slice(-4)}` : value;
}

/** True if the current user may manage users/roles at the whole-org level. */
export function canAdminOrg(
  superuser: boolean,
  orgRights: UserOrgRight[],
  orgId: string,
): boolean {
  if (superuser) return true;
  const org = orgRights.find((o) => o.orgIdentifier === orgId);
  return org?.functions.some((f) => f.function === '*' && f.right === 'admin') ?? false;
}

/** True if the current user may manage users/roles for a specific function within an org. */
export function canAdminFunction(
  superuser: boolean,
  orgRights: UserOrgRight[],
  orgId: string,
  functionId: string,
): boolean {
  if (superuser) return true;
  const org = orgRights.find((o) => o.orgIdentifier === orgId);
  return (
    org?.functions.some(
      (f) => (f.function === '*' || f.function === functionId) && f.right === 'admin',
    ) ?? false
  );
}
