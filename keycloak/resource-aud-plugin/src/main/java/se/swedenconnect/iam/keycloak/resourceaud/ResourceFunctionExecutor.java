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

import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.keycloak.OAuthErrorException;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.utils.OAuth2CodeParser;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.TokenRequestContext;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Keycloak Client Policy Executor that validates the OAuth2 {@code resource} parameter
 * (RFC 8707) against the target client's {@code client_functions} attribute.
 *
 * <p>When a token or authorization request carries a {@code resource} parameter, this executor:
 * <ol>
 *   <li>Extracts the function identifier from the requested scope.</li>
 *   <li>Looks up the client identified by the {@code resource} value.</li>
 *   <li>Checks that the client's {@code client_functions} attribute contains the function.</li>
 *   <li>Throws a {@link ClientPolicyException} with {@code invalid_target} if validation fails.</li>
 *   <li>For authorization requests, stores the validated resource value in an auth session note
 *       so the {@link ResourceAudienceMapper} can read it at token generation time.</li>
 * </ol>
 *
 * <p>If {@code client_functions} is absent or blank on the resource server client, it is treated
 * as function-universal and all functions are accepted.</p>
 *
 * <p>On token requests the executor additionally enforces <em>scope entitlement</em>: every
 * requested scope of the form {@code {org}:{function}:{right}} must be backed by a group
 * membership under {@code /orgs/{org}}. KeyCloak grants optional client scopes to whoever asks
 * for them and never evaluates the Authorization Services permissions during standard token
 * issuance, so without this check any authenticated user of a managed client could obtain any
 * organization's scope. Requests carrying an unentitled scope are rejected with
 * {@code invalid_scope}.</p>
 *
 * <p>Service account token requests are exempt: the token is issued to the client itself rather
 * than to a user, so there is no group membership to check and only the resource/function
 * validation applies.</p>
 *
 * @author Martin Lindström
 * @author Felix Hellman
 */
public class ResourceFunctionExecutor
    implements ClientPolicyExecutorProvider<ClientPolicyExecutorConfigurationRepresentation> {

  private static final Logger LOG = Logger.getLogger(ResourceFunctionExecutor.class);

  private final KeycloakSession session;

  public ResourceFunctionExecutor(final KeycloakSession session) {
    this.session = session;
  }

  @Override
  public String getProviderId() {
    return ResourceFunctionExecutorFactory.PROVIDER_ID;
  }

  @Override
  public void executeOnEvent(final ClientPolicyContext context) throws ClientPolicyException {
    switch (context.getEvent()) {
      case AUTHORIZATION_REQUEST -> handleAuthorizationRequest();
      case TOKEN_REQUEST -> {
        handleTokenRequest();
        validateScopeEntitlement(context);
      }
      case SERVICE_ACCOUNT_TOKEN_REQUEST -> handleTokenRequest();
      default -> LOG.debugf("Skipping resource validation for event: %s", context.getEvent());
    }
  }

  private void handleAuthorizationRequest() throws ClientPolicyException {
    final var httpRequest = this.session.getContext().getHttpRequest();
    if (httpRequest == null) {
      return;
    }

    // Authorization requests arrive as GET — resource parameter is in the URI query string.
    // Also check form parameters for POST-based authorization requests.
    String resource = null;
    final var formParams = httpRequest.getDecodedFormParameters();
    if (formParams != null) {
      resource = formParams.getFirst("resource");
    }
    if (resource == null) {
      resource = httpRequest.getUri().getQueryParameters().getFirst("resource");
    }
    if (resource == null) {
      return;
    }

    // Get scope from query params (GET) or form params (POST).
    String scope = null;
    if (formParams != null) {
      scope = formParams.getFirst("scope");
    }
    if (scope == null) {
      scope = httpRequest.getUri().getQueryParameters().getFirst("scope");
    }

    final String function = ScopeUtils.extractFunction(scope);
    if (function == null) {
      return;
    }

    validateResourceFunction(resource, function);

    // Store resource in auth session note so the mapper can read it at token generation time.
    final var authSession = this.session.getContext().getAuthenticationSession();
    if (authSession != null) {
      authSession.setClientNote(ScopeUtils.SESSION_NOTE_KEY, resource);
    }
  }

  private void handleTokenRequest() throws ClientPolicyException {
    final var httpRequest = this.session.getContext().getHttpRequest();
    if (httpRequest == null) {
      return;
    }

    final var formParams = httpRequest.getDecodedFormParameters();
    if (formParams == null) {
      return;
    }

    final String resource = formParams.getFirst("resource");
    if (resource == null) {
      return;
    }

    final String function = ScopeUtils.extractFunction(formParams.getFirst("scope"));
    if (function == null) {
      return;
    }

    validateResourceFunction(resource, function);
  }

  private void validateResourceFunction(final String resource, final String function)
      throws ClientPolicyException {
    final var realm = this.session.getContext().getRealm();
    final var resourceClient = this.session.clients().getClientByClientId(realm, resource);

    if (resourceClient == null) {
      throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST,
          "Resource server not found: " + resource);
    }

    final String clientFunctions = resourceClient.getAttribute("client_functions");
    if (clientFunctions == null || clientFunctions.isBlank()) {
      // Function-universal resource server — accept all functions.
      LOG.debugf("Resource server '%s' has no client_functions — accepting function '%s'",
          resource, function);
      return;
    }

    final boolean supported = Arrays.stream(clientFunctions.split(","))
        .map(String::trim)
        .anyMatch(f -> f.equals(function));

    if (!supported) {
      LOG.infof("Resource server '%s' does not support function '%s' (client_functions: %s)",
          resource, function, clientFunctions);
      throw new ClientPolicyException("invalid_target",
          "Resource server does not support the requested function: " + function);
    }

    LOG.debugf("Resource server '%s' supports function '%s' — validation passed",
        resource, function);
  }

  /**
   * Rejects the token request if the user is not entitled to every org-scoped scope it carries.
   *
   * <p>Entitlement is read from the user's live group memberships rather than from any token: the
   * client presents no token here, and a membership revoked since login must take effect on the
   * next token request.</p>
   *
   * <p>The check fails closed. A request that carries an org scope but no resolvable user is
   * rejected rather than allowed through — reachable only if KeyCloak fires {@code TOKEN_REQUEST}
   * without a resolvable authorization code.</p>
   *
   * @param context the client policy context
   * @throws ClientPolicyException with {@code invalid_scope} if a requested scope is not covered
   *     by the user's group memberships, or if no user can be resolved for the request
   */
  private void validateScopeEntitlement(final @NonNull ClientPolicyContext context)
      throws ClientPolicyException {

    final List<ScopeUtils.OrgScope> orgScopes = ScopeUtils.parseOrgScopes(requestedScope(context));
    if (orgScopes.isEmpty()) {
      LOG.debug("Token request carries no org-scoped scopes — skipping entitlement check");
      return;
    }

    final UserModel user = resolveUser(context);
    if (user == null) {
      LOG.infof("Token request for org scopes %s rejected: no authenticated user on the session",
          orgScopes.stream().map(ScopeUtils.OrgScope::raw).toList());
      throw new ClientPolicyException(OAuthErrorException.INVALID_SCOPE,
          "Org-scoped scopes require an authenticated user");
    }

    final RealmModel realm = this.session.getContext().getRealm();
    final RoleModel superuser = realm.getRole(ScopeUtils.REALM_ROLE_SUPERUSER);
    if (superuser != null && user.hasRole(superuser)) {
      LOG.debugf("User '%s' has realm role '%s' — all org scopes granted",
          user.getUsername(), ScopeUtils.REALM_ROLE_SUPERUSER);
      return;
    }

    final Set<String> memberships = groupPathsOf(user);
    for (final ScopeUtils.OrgScope scope : orgScopes) {
      final Set<String> qualifying = ScopeUtils.qualifyingGroupPaths(
          scope.organizationIdentifier(), scope.function(), scope.right());
      if (Collections.disjoint(memberships, qualifying)) {
        LOG.infof("Scope '%s' rejected for user '%s': holds none of the qualifying groups %s",
            scope.raw(), user.getUsername(), qualifying);
        throw new ClientPolicyException(OAuthErrorException.INVALID_SCOPE,
            "User is not entitled to the requested scope: " + scope.raw());
      }
    }
    LOG.debugf("User '%s' is entitled to all %d requested org scope(s)",
        user.getUsername(), orgScopes.size());
  }

  /**
   * Returns the scope string the token request applies to.
   *
   * <p>An authorization code exchange normally carries no {@code scope} form parameter — the scope
   * was fixed at the authorization request and is held as a client session note. That note is
   * therefore the authoritative source, with the form parameter as a fallback for grants that do
   * send one.</p>
   *
   * @param context the client policy context
   * @return the space-separated scope string, or {@code null} if none can be determined
   */
  private @Nullable String requestedScope(final @NonNull ClientPolicyContext context) {
    final AuthenticatedClientSessionModel clientSession = clientSessionOf(context);
    if (clientSession != null) {
      final String note = clientSession.getNote(OIDCLoginProtocol.SCOPE_PARAM);
      if (note != null && !note.isBlank()) {
        return note;
      }
    }
    final var httpRequest = this.session.getContext().getHttpRequest();
    if (httpRequest == null) {
      return null;
    }
    final var formParams = httpRequest.getDecodedFormParameters();
    return formParams == null ? null : formParams.getFirst("scope");
  }

  /**
   * Resolves the user the token request is being made on behalf of.
   *
   * @param context the client policy context
   * @return the user, or {@code null} if the request has no resolvable user session
   */
  private static @Nullable UserModel resolveUser(final @NonNull ClientPolicyContext context) {
    final AuthenticatedClientSessionModel clientSession = clientSessionOf(context);
    if (clientSession == null) {
      return null;
    }
    final UserSessionModel userSession = clientSession.getUserSession();
    return userSession == null ? null : userSession.getUser();
  }

  /**
   * Extracts the authenticated client session from the context of a token request.
   *
   * @param context the client policy context
   * @return the client session, or {@code null} if the context carries none
   */
  private static @Nullable AuthenticatedClientSessionModel clientSessionOf(
      final @NonNull ClientPolicyContext context) {

    if (context instanceof final TokenRequestContext tokenRequest) {
      final OAuth2CodeParser.ParseResult parseResult = tokenRequest.getParseResult();
      return parseResult == null ? null : parseResult.getClientSession();
    }
    return null;
  }

  /**
   * Returns the full paths of every group the user is a direct member of, in the same
   * {@code /orgs/{org}/{function}/_right} form the Authorization Services group policies use.
   *
   * @param user the user
   * @return the group paths; never {@code null}
   */
  private static @NonNull Set<String> groupPathsOf(final @NonNull UserModel user) {
    return user.getGroupsStream()
        .map(ResourceFunctionExecutor::groupPath)
        .collect(Collectors.toSet());
  }

  /**
   * Builds the full path of a group by walking up its parent chain.
   *
   * @param group the group
   * @return the group path, e.g. {@code /orgs/2021006883/demo/_admin}
   */
  private static @NonNull String groupPath(final @NonNull GroupModel group) {
    final Deque<String> segments = new ArrayDeque<>();
    for (GroupModel current = group; current != null; current = current.getParent()) {
      segments.addFirst(current.getName());
    }
    return "/" + String.join("/", segments);
  }
}
