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

/**
 * The page numbers to offer for a paged list, 0-based, with 'ellipsis' standing for the pages
 * left out. Up to seven pages are all offered; beyond that the first, the last and the pages
 * around the current one are.
 */
export function getPageNumbers(current: number, total: number): (number | 'ellipsis')[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i);
  const pages: (number | 'ellipsis')[] = [0];
  if (current > 2) pages.push('ellipsis');
  for (let i = Math.max(1, current - 1); i <= Math.min(total - 2, current + 1); i++) {
    pages.push(i);
  }
  if (current < total - 3) pages.push('ellipsis');
  pages.push(total - 1);
  return pages;
}

/**
 * The characters Keycloak refuses in a person's name, as its
 * `person-name-prohibited-characters` validator lists them. The realm validates `firstName` and
 * `lastName` with it, so a name carrying one of these is refused when the user is saved.
 *
 * `\p{Cntrl}` in the validator's own pattern is written out here as the control ranges, which is
 * what it means. This is the only copy of the rule: a name it lets through that Keycloak still
 * refuses is answered by the API as a bad request.
 */
const ALLOWED_PERSON_NAME = /^[^<>&"\v$%!#?§;*~/\\|^=[\]{}()\u0000-\u001F\u007F]+$/;

/** The prohibited characters, for a message that says which ones to remove. */
export const PROHIBITED_NAME_CHARACTERS = '< > & " $ % ! # ? § ; * ~ / \\ | ^ = [ ] { } ( )';

/**
 * True when Keycloak would accept this name. An empty name is not a name; a caller that reports
 * a missing name separately checks for that first.
 */
export function isAllowedPersonName(name: string): boolean {
  return ALLOWED_PERSON_NAME.test(name);
}
