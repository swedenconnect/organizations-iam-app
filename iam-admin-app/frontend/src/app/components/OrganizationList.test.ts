import { describe, expect, it } from 'vitest';
import { countUsersWithAccess } from '@/app/components/OrganizationList';

/** The organization-level list as the expanded panel builds it. */
function orgUsers(...ids: string[]) {
  return ids.map((id) => ({ user: { id } }));
}

/** One function of the organization, with the users listed under it. */
function func(...ids: string[]) {
  return { users: ids.map((id) => ({ user: { id } })) };
}

describe('countUsersWithAccess', () => {
  it('counts the organization-level users', () => {
    expect(countUsersWithAccess(orgUsers('a', 'b'), [])).toBe(2);
  });

  it('counts the users of the attached functions as well', () => {
    expect(countUsersWithAccess(orgUsers('a'), [func('b'), func('c')])).toBe(3);
  });

  it('counts a user holding both an organization-level and a function right once', () => {
    expect(countUsersWithAccess(orgUsers('a'), [func('a', 'b')])).toBe(2);
  });

  it('counts a user holding rights on several functions once', () => {
    expect(countUsersWithAccess([], [func('a'), func('a'), func('b')])).toBe(2);
  });

  it('counts an organization whose users are all on functions', () => {
    expect(countUsersWithAccess([], [func('a', 'b')])).toBe(2);
  });

  it('counts an organization without any users as none', () => {
    expect(countUsersWithAccess([], [])).toBe(0);
    expect(countUsersWithAccess([], [func()])).toBe(0);
  });

  it('skips an organization-level entry whose user could not be resolved', () => {
    expect(countUsersWithAccess([{ user: undefined }, ...orgUsers('a')], [])).toBe(1);
  });
});
