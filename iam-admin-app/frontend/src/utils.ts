import { Organization, UserOrgRight } from '@/types';

/**
 * Resolves the name to show for an organization: the display name for the given language, the
 * display name in the other language if that one is not set, and the legal name if no display name
 * has been given at all. Never returns an empty string.
 */
export function resolveOrgName(org: Organization, language: string): string {
  const preferred = language === 'sv' ? org.nameSv : org.nameEn;
  const other = language === 'sv' ? org.nameEn : org.nameSv;
  return preferred?.trim() || other?.trim() || org.legalName;
}

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

/**
 * True if the current user may manage users/roles at the whole-org level. This requires admin
 * granted at the organization level, which is stricter than admin on a single function.
 */
export function canAdminOrg(
  superuser: boolean,
  orgRights: UserOrgRight[],
  orgId: string,
): boolean {
  if (superuser) return true;
  const org = orgRights.find((o) => o.orgIdentifier === orgId);
  return org?.orgLevelRight === 'admin';
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
    org?.functions.some((f) => f.function === functionId && f.right === 'admin') ?? false
  );
}
