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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * The rule Keycloak applies to a person's name, so that a name it would refuse is refused here
 * first, with a message naming the problem instead of a server error.
 *
 * <p>The realm's user profile validates {@code firstName} and {@code lastName} with Keycloak's
 * {@code person-name-prohibited-characters} validator, which answers {@code 400} for a name
 * carrying any of the characters below. The pattern is that validator's own, copied from
 * {@code PersonNameProhibitedCharactersValidator} in Keycloak 26.8.1 — a name is allowed when it
 * matches.</p>
 *
 * <p>A copy can fall behind the realm it mirrors, so it is not the only guard: a name Keycloak
 * refuses for a reason this rule does not know is still reported as a 400, see
 * {@code KeycloakErrorAdvice}.</p>
 *
 * @author Per Fredrik Plars
 */
public final class PersonNameRule {

  /** Keycloak's {@code person-name-prohibited-characters} pattern. A name must match it. */
  private static final Pattern ALLOWED =
      Pattern.compile("^[^<>&\"\\v$%!#?§;*~/\\\\|^=\\[\\]{}()\\p{Cntrl}]+$");

  /** The characters the rule refuses, for a message to the caller. */
  public static final String PROHIBITED_CHARACTERS = "< > & \" $ % ! # ? § ; * ~ / \\ | ^ = [ ] { } ( )";

  private PersonNameRule() {
  }

  /**
   * Tells whether Keycloak would accept this name. A {@code null} or empty name is not allowed;
   * a caller that reports a missing name separately checks for that first.
   *
   * @param name the name to check
   * @return {@code true} when the name carries no prohibited character
   */
  public static boolean isAllowed(final @Nullable String name) {
    return name != null && ALLOWED.matcher(name).matches();
  }

  /**
   * The message for a name the rule refuses.
   *
   * @return the message
   */
  public static @NonNull String rejectionMessage() {
    return "name must not contain any of the characters " + PROHIBITED_CHARACTERS;
  }

}
