import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { User, UserData } from '@/types';

const apiFetch = vi.fn();

vi.mock('@/lib/api', () => ({
  apiUrl: (path: string) => `/${path}`,
  apiFetch: (input: string) => apiFetch(input),
}));

const { fetchAllUsers, deriveUserRoles, createUser, updateUser, DuplicateIdentityError,
  EmailTakenError, UserIdTakenError } = await import('@/services/userService');

/** A minimal user as GET /api/users returns it. */
function userData(userId: string): UserData {
  return {
    userId,
    username: userId,
    firstName: null,
    lastName: null,
    email: null,
    personalIdentityNumber: null,
    superuser: false,
    rights: [],
  };
}

/**
 * Serves `total` users in pages of the requested size. Pages listed in `failing` answer 500.
 */
function serveUsers(total: number, failing: number[] = []) {
  apiFetch.mockImplementation(async (url: string) => {
    const params = new URL(url, 'http://localhost').searchParams;
    const page = Number(params.get('page'));
    const size = Number(params.get('size'));
    if (failing.includes(page)) {
      return new Response(null, { status: 500 });
    }
    const content = Array.from({ length: total }, (_, i) => userData(`u${i}`)).slice(page * size, (page + 1) * size);
    return new Response(JSON.stringify({
      content,
      totalElements: total,
      page,
      size,
      totalPages: Math.ceil(total / size),
    }));
  });
}

/** The page numbers requested so far. */
function requestedPages(): number[] {
  return apiFetch.mock.calls.map(([url]) => Number(new URL(url, 'http://localhost').searchParams.get('page')));
}

describe('fetchAllUsers', () => {
  beforeEach(() => {
    apiFetch.mockReset();
  });

  it('sends one request when there are no users', async () => {
    serveUsers(0);
    const result = await fetchAllUsers();
    expect(result.content).toEqual([]);
    expect(apiFetch).toHaveBeenCalledTimes(1);
  });

  it('sends one request when all users fit in one page', async () => {
    serveUsers(500);
    const result = await fetchAllUsers();
    expect(result.content).toHaveLength(500);
    expect(result.totalElements).toBe(500);
    expect(requestedPages()).toEqual([0]);
  });

  it('fetches the remaining pages when there are more users than one page holds', async () => {
    serveUsers(1201);
    const result = await fetchAllUsers();
    expect(requestedPages()).toEqual([0, 1, 2]);
    expect(result.content).toHaveLength(1201);
    expect(result.content[0].userId).toBe('u0');
    expect(result.content[1200].userId).toBe('u1200');
    expect(result.totalElements).toBe(1201);
  });

  it('requests pages of 500 users', async () => {
    serveUsers(10);
    await fetchAllUsers();
    expect(apiFetch.mock.calls[0][0]).toContain('size=500');
  });

  it('fails when the first page request fails', async () => {
    serveUsers(10, [0]);
    await expect(fetchAllUsers()).rejects.toThrow('Failed to fetch users: 500');
  });

  it('fails when a later page request fails', async () => {
    serveUsers(1201, [2]);
    await expect(fetchAllUsers()).rejects.toThrow('Failed to fetch users: 500');
  });
});

describe('deriveUserRoles', () => {
  /** A user holding the given rights. */
  function user(id: string, rights?: User['rights']): User {
    return { id, name: id, email: '', personalIdentityNumber: '', rights };
  }

  it('returns nothing for no users', () => {
    expect(deriveUserRoles([])).toEqual([]);
  });

  it('returns nothing for users without rights', () => {
    expect(deriveUserRoles([user('a'), user('b', [])])).toEqual([]);
  });

  it('maps an organization-level right', () => {
    expect(deriveUserRoles([user('a', [{ orgIdentifier: 'org1', functionId: null, right: 'admin' }])])).toEqual([
      { id: 'a:org1:*', userId: 'a', organizationId: 'org1', functionId: undefined, role: 'admin' },
    ]);
  });

  it('maps a function right', () => {
    expect(deriveUserRoles([user('a', [{ orgIdentifier: 'org1', functionId: 'f1', right: 'read' }])])).toEqual([
      { id: 'a:org1:f1', userId: 'a', organizationId: 'org1', functionId: 'f1', role: 'read' },
    ]);
  });

  it('returns one entry per right across all users', () => {
    const roles = deriveUserRoles([
      user('a', [
        { orgIdentifier: 'org1', functionId: null, right: 'write' },
        { orgIdentifier: 'org1', functionId: 'f1', right: 'admin' },
      ]),
      user('b', [{ orgIdentifier: 'org2', functionId: null, right: 'read' }]),
    ]);
    expect(roles.map((r) => r.id)).toEqual(['a:org1:*', 'a:org1:f1', 'b:org2:*']);
  });
});

/**
 * How the backend's answers are turned into errors the form can report against the right field.
 * A 409 with reason EMAIL_TAKEN is the answer to an email address that is already registered,
 * see issue #184, and must not be mistaken for a duplicate eID identity.
 */
describe('conflict handling', () => {
  beforeEach(() => {
    apiFetch.mockReset();
  });

  /** The backend refusing the write with the given body. */
  function conflict(body: unknown) {
    apiFetch.mockResolvedValue(new Response(JSON.stringify(body), { status: 409 }));
  }

  it('reports an email address that is already registered when creating a user', async () => {
    conflict({ reason: 'EMAIL_TAKEN' });

    await expect(createUser({ name: 'Klara', email: 'klara@example.com' } as never))
      .rejects.toBeInstanceOf(EmailTakenError);
  });

  it('reports an email address that is already registered when updating a user', async () => {
    conflict({ reason: 'EMAIL_TAKEN' });

    await expect(updateUser('target-uuid', { name: 'Klara', email: 'klara@example.com' }))
      .rejects.toBeInstanceOf(EmailTakenError);
  });

  it('keeps a taken user ID apart from a taken email address', async () => {
    conflict({ reason: 'USER_ID_TAKEN' });

    await expect(createUser({ name: 'Klara', userId: 'klara' } as never))
      .rejects.toBeInstanceOf(UserIdTakenError);
  });

  it('still reports a duplicate eID identity as such', async () => {
    conflict({ existingUserId: 'other-uuid' });

    await expect(createUser({ name: 'Klara', personalIdentityNumber: '196911292032' } as never))
      .rejects.toBeInstanceOf(DuplicateIdentityError);
  });
});
