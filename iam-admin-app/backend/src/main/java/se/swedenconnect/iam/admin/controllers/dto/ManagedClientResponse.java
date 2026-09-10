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
package se.swedenconnect.iam.admin.controllers.dto;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * A managed Keycloak client, as returned by the client administration endpoints.
 *
 * @param id the Keycloak UUID of the client; used to address it in the per-client endpoints,
 *     because a client_id is a URL and cannot be carried in a path segment
 * @param oidcClient whether the client obtains org-scoped tokens
 * @param resourceServer whether the client may be named as an OAuth2 {@code resource} target
 * @param clientId the OAuth2 client_id
 * @param name the display name, or {@code null}
 * @param functions the functions the client handles
 * @param allFunctions whether the client handles every function in the realm, including the ones
 *     not created yet; set by script only, and it makes {@code functions} a snapshot rather than
 *     the limit
 * @param redirectUris the client's redirect URIs
 * @param jwksUri the JWKS URI, or {@code null} if the client uses an inline JWK Set
 * @param jwksString the inline JWK Set, or {@code null} if the client uses a JWKS URI
 * @param serviceAccount whether the client has a service account user
 * @param orgRightsIdToken whether {@code org_rights} is emitted in the ID token
 * @param orgRightsAccessToken whether {@code org_rights} is emitted in the access token
 * @param enabled whether the client is enabled in Keycloak
 *
 * @author Felix Hellman
 */
public record ManagedClientResponse(
    @NonNull String id,
    boolean oidcClient,
    boolean resourceServer,
    @NonNull String clientId,
    @Nullable String name,
    @NonNull Set<String> functions,
    boolean allFunctions,
    @NonNull List<String> redirectUris,
    @Nullable String jwksUri,
    @Nullable String jwksString,
    boolean serviceAccount,
    boolean orgRightsIdToken,
    boolean orgRightsAccessToken,
    boolean enabled) {
}
