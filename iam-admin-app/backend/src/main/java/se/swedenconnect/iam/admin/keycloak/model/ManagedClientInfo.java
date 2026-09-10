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
 * <p>A client is managed when, and only when, it carries the attribute
 * {@code iam_admin_managed=true}. Managed clients receive the OAuth2 client scopes, Authorization
 * Services scopes, group policies and scope permissions that correspond to the org/function
 * combinations they handle.</p>
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
 * <p>The one exception is the {@code allFunctions} marker, set by the
 * {@code iam_admin_all_functions=true} attribute. A client carrying it handles every function in
 * the realm, including the ones not created yet, so its declared list is never the limit. The
 * marker exists for the IAM Admin application itself, whose API is a resource server for every
 * function. It is set by script only, never through the application's own client API.</p>
 *
 * <p>A marked client still keeps {@code client_functions} materialized to the concrete list of
 * functions that exist, because {@code resource-aud-plugin} runs inside KeyCloak and validates the
 * OAuth2 {@code resource} parameter against that raw attribute, where the marker is not visible to
 * it. The marker is what this application acts on; the materialized list is what KeyCloak acts
 * on.</p>
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
    boolean allFunctions,
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
   * <p>A client carrying the {@code allFunctions} marker handles every function, whether or not it
   * appears in the declared list. The declared list can lag behind — a function created while the
   * application could not reach KeyCloak is never written to it — and reconciliation must still
   * cover the function.</p>
   *
   * @param functionId the function identifier
   * @return {@code true} if the client handles the function
   */
  public boolean handles(final @NonNull String functionId) {
    return this.allFunctions || this.functions.contains(functionId);
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
   * <p>A client carrying the {@code allFunctions} marker is never unscoped, even before any
   * function exists: it handles whatever the realm comes to hold.</p>
   *
   * @return {@code true} if the client declares no functions
   */
  public boolean unscoped() {
    return !this.allFunctions && this.functions.isEmpty();
  }
}
