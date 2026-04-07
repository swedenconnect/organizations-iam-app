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

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
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
}
