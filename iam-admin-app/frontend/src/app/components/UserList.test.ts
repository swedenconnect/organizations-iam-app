/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { describe, expect, it } from 'vitest';
import { pageHoldingUser, pageOfUsers } from '@/app/components/UserList';

/** A list of users, identified by id only, as the paging helpers see them. */
function users(count: number) {
  return Array.from({ length: count }, (_, i) => ({ id: `user-${i}` }));
}

describe('pageOfUsers', () => {
  it('cuts the list into pages of the given size', () => {
    const { content, currentPage, totalPages } = pageOfUsers(users(12), 1, 5);
    expect(content.map((u) => u.id)).toEqual(['user-5', 'user-6', 'user-7', 'user-8', 'user-9']);
    expect(currentPage).toBe(1);
    expect(totalPages).toBe(3);
  });

  it('gives a last page that is not full', () => {
    expect(pageOfUsers(users(12), 2, 5).content.map((u) => u.id)).toEqual(['user-10', 'user-11']);
  });

  it('shows the last page when asked for one beyond the end', () => {
    const { content, currentPage } = pageOfUsers(users(12), 9, 5);
    expect(currentPage).toBe(2);
    expect(content).toHaveLength(2);
  });

  it('is one empty page when there are no users', () => {
    expect(pageOfUsers([], 0, 5)).toEqual({ content: [], currentPage: 0, totalPages: 1 });
  });

  it('is a single page when everyone fits on it', () => {
    expect(pageOfUsers(users(5), 0, 5).totalPages).toBe(1);
  });
});

describe('pageHoldingUser', () => {
  /** What makes a user created moments ago reachable: the list turns to their page. */
  it('finds the page a user is on', () => {
    expect(pageHoldingUser(users(12), 'user-0', 5)).toBe(0);
    expect(pageHoldingUser(users(12), 'user-5', 5)).toBe(1);
    expect(pageHoldingUser(users(12), 'user-11', 5)).toBe(2);
  });

  it('reports no page for a user the list does not hold', () => {
    expect(pageHoldingUser(users(3), 'nobody', 5)).toBeNull();
    expect(pageHoldingUser([], 'nobody', 5)).toBeNull();
  });
});
