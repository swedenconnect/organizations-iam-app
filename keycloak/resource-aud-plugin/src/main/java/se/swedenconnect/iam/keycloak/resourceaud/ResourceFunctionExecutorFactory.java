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

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory;

import java.util.List;

/**
 * Factory for {@link ResourceFunctionExecutor}.
 *
 * @author Martin Lindström
 */
public class ResourceFunctionExecutorFactory implements ClientPolicyExecutorProviderFactory {

  public static final String PROVIDER_ID = "resource-function-executor";

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public ClientPolicyExecutorProvider create(final KeycloakSession session) {
    return new ResourceFunctionExecutor(session);
  }

  @Override
  public void init(final Config.Scope config) {
    // No initialization needed.
  }

  @Override
  public void postInit(final KeycloakSessionFactory factory) {
    // No post-initialization needed.
  }

  @Override
  public void close() {
    // No resources to release.
  }

  @Override
  public String getHelpText() {
    return "Validates the OAuth2 resource parameter (RFC 8707) against the target client's "
        + "client_functions attribute. Rejects requests where the function extracted from the "
        + "requested scope is not listed in client_functions. Stores the validated resource "
        + "value in an auth session note for the Resource Audience Mapper to use at token time.";
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return List.of();
  }
}
