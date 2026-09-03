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
 * Request body for registering a managed Keycloak client.
 *
 * @param clientId the OAuth2 client_id; also used as the client root URL
 * @param name the display name or description, or {@code null}
 * @param oidcClient whether the client obtains org-scoped tokens; requires redirect URIs and JWKS
 * @param resourceServer whether other clients may name it as an OAuth2 {@code resource} target
 * @param redirectUris the redirect URIs; at least one, and none may contain a wildcard
 * @param functions the functions the client handles; at least one
 * @param jwksUri the JWKS URI; exactly one of {@code jwksUri} and {@code jwksString} must be given
 * @param jwksString the inline JWK Set; exactly one of {@code jwksUri} and {@code jwksString} must
 *     be given
 * @param serviceAccount whether to keep the service account user; defaults to {@code false}
 * @param orgRightsIdToken whether {@code org_rights} is emitted in the ID token; defaults to
 *     {@code true}
 * @param orgRightsAccessToken whether {@code org_rights} is emitted in the access token; defaults
 *     to {@code true}
 *
 * @author Felix Hellman
 */
public record CreateManagedClientRequest(
    @NonNull String clientId,
    @Nullable String name,
    @Nullable Boolean oidcClient,
    @Nullable Boolean resourceServer,
    @Nullable List<String> redirectUris,
    @Nullable Set<String> functions,
    @Nullable String jwksUri,
    @Nullable String jwksString,
    @Nullable Boolean serviceAccount,
    @Nullable Boolean orgRightsIdToken,
    @Nullable Boolean orgRightsAccessToken) {
}
