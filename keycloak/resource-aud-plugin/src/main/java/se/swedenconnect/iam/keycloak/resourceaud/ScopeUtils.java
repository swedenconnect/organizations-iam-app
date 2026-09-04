/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.swedenconnect.iam.keycloak.resourceaud;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared utilities for scope parsing used by the resource-aud plugin components.
 *
 * @author Martin Lindström
 */
final class ScopeUtils {

  /** Auth session note key used to pass the validated {@code resource} parameter from the
   *  Client Policy Executor (authorization request) to the Protocol Mapper (token generation). */
  static final String SESSION_NOTE_KEY = "resource_parameter";

  /** The right levels an org-scoped scope may carry, weakest first. */
  static final List<String> RIGHT_LEVELS = List.of("read", "write", "admin");

  /** Name of the realm role that grants unconditional access to every org scope. */
  static final String REALM_ROLE_SUPERUSER = "superuser";

  /** Name of the top-level group under which all organization groups live. */
  static final String GROUP_ORGS = "orgs";

  private ScopeUtils() {}

  /**
   * Extracts the function identifier from a space-separated scope string.
   * Scopes follow the pattern {@code {org}:{function}:{right}}.
   *
   * @param scopeString the space-separated scope string (may be {@code null})
   * @return the function identifier from the first matching scope, or {@code null}
   */
  static @Nullable String extractFunction(final @Nullable String scopeString) {
    if (scopeString == null || scopeString.isBlank()) {
      return null;
    }
    for (final String token : scopeString.split("\\s+")) {
      final String[] parts = token.split(":");
      if (parts.length == 3) {
        return parts[1];
      }
    }
    return null;
  }

  /**
   * Extracts all distinct function identifiers from a space-separated scope string.
   * Scopes follow the pattern {@code {org}:{function}:{right}}.
   *
   * @param scopeString the space-separated scope string (may be {@code null})
   * @return a set of distinct function identifiers (never {@code null}, may be empty)
   */
  static Set<String> extractAllFunctions(final @Nullable String scopeString) {
    final Set<String> functions = new LinkedHashSet<>();
    if (scopeString == null || scopeString.isBlank()) {
      return functions;
    }
    for (final String token : scopeString.split("\\s+")) {
      final String[] parts = token.split(":");
      if (parts.length == 3) {
        functions.add(parts[1]);
      }
    }
    return functions;
  }

  /**
   * An org-scoped OAuth2 scope, i.e. one of the form {@code {org}:{function}:{right}}.
   *
   * @param raw the scope as it appeared in the request
   * @param organizationIdentifier the organization identifier
   * @param function the function identifier
   * @param right the right level, one of {@link #RIGHT_LEVELS}
   */
  record OrgScope(
      @NonNull String raw,
      @NonNull String organizationIdentifier,
      @NonNull String function,
      @NonNull String right) {
  }

  /**
   * Extracts every org-scoped scope from a space-separated scope string.
   *
   * <p>Tokens that are not of the form {@code {org}:{function}:{right}} with a known right level
   * are ignored — they are ordinary OIDC scopes and carry no organizational entitlement.</p>
   *
   * @param scopeString the space-separated scope string (may be {@code null})
   * @return the org-scoped scopes, in request order; never {@code null}
   */
  static @NonNull List<OrgScope> parseOrgScopes(final @Nullable String scopeString) {
    final List<OrgScope> scopes = new ArrayList<>();
    if (scopeString == null || scopeString.isBlank()) {
      return scopes;
    }
    for (final String token : scopeString.split("\\s+")) {
      final String[] parts = token.split(":");
      if (parts.length == 3 && RIGHT_LEVELS.contains(parts[2])
          && !parts[0].isBlank() && !parts[1].isBlank()) {
        scopes.add(new OrgScope(token, parts[0], parts[1], parts[2]));
      }
    }
    return scopes;
  }

  /**
   * Returns the group paths that entitle a user to an org/function/right combination.
   *
   * <p>A higher right always qualifies for a lower one: {@code _admin} grants write and read,
   * {@code _write} grants read. Both the org-wide groups and the function-specific groups
   * qualify.</p>
   *
   * <p>This mirrors {@code KeycloakAdminClient.qualifyingGroupPaths} in the IAM admin
   * application, which builds the Authorization Services group policies from the same rule.
   * The two must stay in step.</p>
   *
   * @param organizationIdentifier the organization identifier
   * @param function the function identifier
   * @param right the right level, one of {@link #RIGHT_LEVELS}
   * @return the qualifying group paths; empty if the right level is unknown
   */
  static @NonNull Set<String> qualifyingGroupPaths(
      final @NonNull String organizationIdentifier,
      final @NonNull String function,
      final @NonNull String right) {

    final String org = "/" + GROUP_ORGS + "/" + organizationIdentifier;
    final String func = org + "/" + function;
    return switch (right) {
      case "read" -> new LinkedHashSet<>(List.of(
          org + "/_read", org + "/_write", org + "/_admin",
          func + "/_read", func + "/_write", func + "/_admin"));
      case "write" -> new LinkedHashSet<>(List.of(
          org + "/_write", org + "/_admin",
          func + "/_write", func + "/_admin"));
      case "admin" -> new LinkedHashSet<>(List.of(
          org + "/_admin",
          func + "/_admin"));
      default -> Set.of();
    };
  }
}
