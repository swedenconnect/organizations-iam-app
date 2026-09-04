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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A KeyCloak client managed by the IAM Admin application.
 *
 * <p>A client is managed when it carries the attribute {@code iam_admin_managed=true}, or when it
 * is listed in {@code iam.admin.authz-client-ids}. Managed clients receive the OAuth2 client
 * scopes, Authorization Services scopes, group policies and scope permissions that correspond to
 * the org/function combinations they handle.</p>
 *
 * <p>A client plays one or both of two roles, which are independent:</p>
 * <ul>
 *   <li>{@code oidcClient} — it obtains org-scoped tokens. Marked {@code iam_admin_managed=true},
 *       and reconciled: it receives the client scopes, Authorization Services scopes, group
 *       policies and scope permissions for the org/function combinations it handles.</li>
 *   <li>{@code resourceServer} — other clients may name it in the OAuth2 {@code resource}
 *       parameter, putting it in the {@code aud} claim. Marked
 *       {@code iam_admin_resource_server=true}. This role needs no artifacts of its own.</li>
 * </ul>
 *
 * <p>A client that is only a resource server is never reconciled — it never requests a token.</p>
 *
 * <p>The {@code client_functions} attribute lists the functions a client handles, and it is the
 * complete list: a client handles exactly the functions it declares. A client that declares none
 * handles none, and therefore receives no artifacts at all — an empty list is never read as
 * "every function". A client created through the IAM Admin application always declares at least
 * one function; only clients provisioned outside the application can end up unscoped, and
 * {@link #unscoped()} reports that.</p>
 *
 * @author Felix Hellman
 */
public record ManagedClientInfo(
    @NonNull String uuid,
    @NonNull String clientId,
    @Nullable String name,
    boolean oidcClient,
    boolean resourceServer,
    @NonNull Set<String> functions,
    @NonNull List<String> redirectUris,
    @Nullable String jwksUri,
    @Nullable String jwksString,
    boolean serviceAccount,
    boolean orgRightsIdToken,
    boolean orgRightsAccessToken,
    boolean enabled) {

  /**
   * Parses a {@code client_functions} attribute value into a set of function identifiers.
   *
   * <p>The value is a comma-separated list. Blank entries are discarded and surrounding whitespace
   * is trimmed. A {@code null} or blank value yields an empty set.</p>
   *
   * @param attributeValue the raw attribute value, or {@code null} if the attribute is absent
   * @return the function identifiers; never {@code null}
   */
  public static @NonNull Set<String> parseFunctions(final @Nullable String attributeValue) {
    if (attributeValue == null || attributeValue.isBlank()) {
      return Collections.emptySet();
    }
    return Arrays.stream(attributeValue.split(","))
        .map(String::trim)
        .filter(f -> !f.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Tells whether this client handles the given function, i.e. whether it should receive the
   * KeyCloak artifacts created when the function is attached to an organization.
   *
   * @param functionId the function identifier
   * @return {@code true} if the client handles the function
   */
  public boolean handles(final @NonNull String functionId) {
    return this.functions.contains(functionId);
  }

  /**
   * Tells whether this client holds KeyCloak artifacts, i.e. whether reconciliation applies to it.
   * Only the OIDC client role requests tokens and therefore needs scopes, policies and
   * permissions.
   *
   * @return {@code true} if the client plays the OIDC client role
   */
  public boolean reconcilable() {
    return this.oidcClient;
  }

  /**
   * Tells whether this client declares no functions at all, and therefore receives no scopes,
   * policies or permissions for any organization.
   *
   * @return {@code true} if the client declares no functions
   */
  public boolean unscoped() {
    return this.functions.isEmpty();
  }
}
