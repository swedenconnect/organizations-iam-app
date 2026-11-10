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
package se.swedenconnect.iam.admin.keycloak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the rule accepts and refuses the same names as Keycloak's
 * {@code person-name-prohibited-characters} validator. Every expectation below was taken from
 * that validator in Keycloak 26.8.1, run against these very values.
 *
 * @author Per Fredrik Plars
 */
class PersonNameRuleTest {

  /** A name of letters, spaces, hyphens or apostrophes is a name. */
  @ParameterizedTest
  @ValueSource(strings = {
      "Martin Lindström", "Anna-Maria O'Brien", "Ärling Öberg", "Jean-Luc", "Ole-Johan Dahl",
      "a,b", "a b", "Zoë", "李雷"})
  void allowedNames(final String name) {
    assertThat(PersonNameRule.isAllowed(name)).as(name).isTrue();
  }

  /** The markup from issue #136, and every character the validator lists. */
  @ParameterizedTest
  @ValueSource(strings = {
      "E2E XSS <img src=x onerror=\"alert(1)\"> abc",
      "a<b", "a>b", "a&b", "a\"b", "x$y", "100% sure", "a!b", "a#b", "Who?", "a§b", "a;b",
      "a*b", "a~b", "a/b", "a\\b", "a|b", "a^b", "a=b", "a[b]", "a{b}", "a(b)", "a\tb", "a\u000bb"})
  void refusedNames(final String name) {
    assertThat(PersonNameRule.isAllowed(name)).as(name).isFalse();
  }

  /** Nothing is not a name either, though a caller reports a missing name on its own. */
  @Test
  void nullAndEmptyAreNotAllowed() {
    assertThat(PersonNameRule.isAllowed(null)).isFalse();
    assertThat(PersonNameRule.isAllowed("")).isFalse();
  }

  /** The message names the characters, so the administrator can see what to remove. */
  @Test
  void rejectionMessage_namesTheCharacters() {
    assertThat(PersonNameRule.rejectionMessage()).contains("<", ">", "&", "$", "%");
  }

}
