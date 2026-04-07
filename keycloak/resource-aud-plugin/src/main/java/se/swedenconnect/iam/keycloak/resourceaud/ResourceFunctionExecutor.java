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
import org.keycloak.OAuthErrorException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;

import java.util.Arrays;

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
 * @author Martin Lindström
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
      case TOKEN_REQUEST, SERVICE_ACCOUNT_TOKEN_REQUEST -> handleTokenRequest();
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

}
