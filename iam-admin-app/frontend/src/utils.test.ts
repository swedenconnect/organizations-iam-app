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
import { isAllowedPersonName } from '@/utils';

/**
 * The same rule the backend applies, which is Keycloak's own
 * `person-name-prohibited-characters` validator. Every expectation below was taken from that
 * validator in Keycloak 26.8.1, run against these very values, so the two cannot drift apart
 * unnoticed. See issue #136.
 */
describe('isAllowedPersonName', () => {
  it.each([
    'Martin Lindström',
    "Anna-Maria O'Brien",
    'Ärling Öberg',
    'Jean-Luc',
    'Ole-Johan Dahl',
    'a,b',
    'a b',
  ])('accepts %s', (name) => {
    expect(isAllowedPersonName(name)).toBe(true);
  });

  it.each([
    'E2E XSS <img src=x onerror="alert(1)"> abc',
    'a<b',
    'a>b',
    'a&b',
    'a"b',
    'x$y',
    '100% sure',
    'a!b',
    'a#b',
    'Who?',
    'a§b',
    'a;b',
    'a*b',
    'a~b',
    'a/b',
    'a\\b',
    'a|b',
    'a^b',
    'a=b',
    'a[b]',
    'a{b}',
    'a(b)',
    'a\tb',
    '\u000b',
  ])('refuses %j', (name) => {
    expect(isAllowedPersonName(name)).toBe(false);
  });

  it('refuses an empty name', () => {
    expect(isAllowedPersonName('')).toBe(false);
  });
});
