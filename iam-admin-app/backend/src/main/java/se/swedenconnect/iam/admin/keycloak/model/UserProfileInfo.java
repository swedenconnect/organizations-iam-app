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
package se.swedenconnect.iam.admin.keycloak.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A realm's declarative user profile, as far as writing user attributes is concerned.
 *
 * <p>Keycloak stores an attribute only when the profile declares it, or when the realm's
 * unmanaged attribute policy lets an administrator write one it does not. An attribute that
 * neither covers is discarded, and the write still answers {@code 204}.</p>
 *
 * @param declaredAttributes the names the profile declares
 * @param unmanagedAttributePolicy the realm's policy for attributes it does not declare, or
 *          {@code null} when the realm sets none, which means that they are discarded
 *
 * @author Per Fredrik Plars
 */
public record UserProfileInfo(
    @NonNull Set<String> declaredAttributes,
    @Nullable String unmanagedAttributePolicy) {

  /** Policies under which an administrator may write an attribute the profile does not declare. */
  private static final Set<String> WRITABLE_POLICIES = Set.of("ENABLED", "ADMIN_EDIT");

  /**
   * Tells whether this realm keeps an attribute that the profile does not declare, when the
   * administrative API writes it.
   *
   * @return {@code true} when such an attribute is kept
   */
  public boolean keepsUndeclaredAttributes() {
    return this.unmanagedAttributePolicy != null
        && WRITABLE_POLICIES.contains(this.unmanagedAttributePolicy.toUpperCase(Locale.ROOT));
  }

  /**
   * The names among {@code written} that this realm discards: neither declared, nor allowed by an
   * unmanaged attribute policy. The result is sorted, so that it reads the same every time it is
   * logged.
   *
   * @param written the attribute names the application writes
   * @return the names that would be discarded, sorted; empty when the realm keeps all of them
   */
  public @NonNull List<String> discarded(final @NonNull Set<String> written) {
    if (this.keepsUndeclaredAttributes()) {
      return List.of();
    }
    return written.stream()
        .filter(name -> !this.declaredAttributes.contains(name))
        .sorted()
        .toList();
  }

}
