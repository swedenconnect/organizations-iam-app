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
package se.swedenconnect.iam.keycloak.idpmatcher;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import java.util.List;

/**
 * Factory for {@link IdpDetectExistingUserByAttributeAuthenticator}.
 *
 * <p>The authenticator is stateless and entirely configuration driven, so a single instance is
 * shared across identity providers and authentication sessions.</p>
 *
 * @author Martin Lindström
 */
public class IdpDetectExistingUserByAttributeAuthenticatorFactory implements AuthenticatorFactory {

  // ---- Provider identity ----

  /** The provider id, as referenced from an authentication flow execution. */
  public static final String PROVIDER_ID = "idp-detect-existing-user-by-attr";

  /** The name shown for this execution in the admin console. */
  public static final String DISPLAY_TYPE = "Detect Existing Broker User By Attribute";

  /** The reference category shown in the admin console. */
  public static final String REFERENCE_CATEGORY = "brokerUserMatch";

  /** The help text shown for this execution in the admin console. */
  public static final String HELP_TEXT =
      "Resolves the incoming brokered identity to an existing local user by matching on a configured "
          + "user attribute, and hands the match to 'Automatically Set Existing User'. Never creates "
          + "users. Fails the flow unless exactly one enabled, permitted user matches.";

  // ---- Configuration keys ----

  /** Configuration key: the user attribute to match the brokered identity on. Required. */
  public static final String MATCH_ATTRIBUTE = "matchAttribute";

  /** Configuration key: a realm role the matched user must hold. Optional. */
  public static final String REQUIRED_ROLE = "requiredRole";

  /** Configuration key: a realm role the matched user must not hold. Optional. */
  public static final String FORBIDDEN_ROLE = "forbiddenRole";

  /**
   * The only permitted requirement. The authenticator is the gate for unknown identities, so an
   * ALTERNATIVE or DISABLED execution would defeat its purpose.
   */
  private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
      AuthenticationExecutionModel.Requirement.REQUIRED
  };

  private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = ProviderConfigurationBuilder.create()
      .property()
      .name(MATCH_ATTRIBUTE)
      .label("Match attribute")
      .helpText("The user attribute to match the incoming brokered identity on, for example "
          + "personalIdentityNumber. The value is read from the brokered identity context, where the "
          + "identity provider's attribute or claim mappers must have placed it.")
      .type(ProviderConfigProperty.STRING_TYPE)
      .required(true)
      .add()
      .property()
      .name(REQUIRED_ROLE)
      .label("Required role")
      .helpText("Optional. The name of a realm role the matched user must hold. If the role does not "
          + "exist in the realm, the flow fails.")
      .type(ProviderConfigProperty.STRING_TYPE)
      .required(false)
      .add()
      .property()
      .name(FORBIDDEN_ROLE)
      .label("Forbidden role")
      .helpText("Optional. The name of a realm role the matched user must not hold. If the role does "
          + "not exist in the realm, the flow fails.")
      .type(ProviderConfigProperty.STRING_TYPE)
      .required(false)
      .add()
      .build();

  private static final IdpDetectExistingUserByAttributeAuthenticator SINGLETON =
      new IdpDetectExistingUserByAttributeAuthenticator();

  // -------------------------------------------------------------------------

  @Override
  public @NonNull Authenticator create(final KeycloakSession session) {
    return SINGLETON;
  }

  @Override
  public void init(final Config.Scope config) {
  }

  @Override
  public void postInit(final KeycloakSessionFactory factory) {
  }

  @Override
  public void close() {
  }

  @Override
  public @NonNull String getId() {
    return PROVIDER_ID;
  }

  @Override
  public @NonNull String getDisplayType() {
    return DISPLAY_TYPE;
  }

  @Override
  public @NonNull String getReferenceCategory() {
    return REFERENCE_CATEGORY;
  }

  @Override
  public @NonNull String getHelpText() {
    return HELP_TEXT;
  }

  @Override
  public boolean isConfigurable() {
    return true;
  }

  @Override
  public AuthenticationExecutionModel.@NonNull Requirement[] getRequirementChoices() {
    return REQUIREMENT_CHOICES.clone();
  }

  @Override
  public boolean isUserSetupAllowed() {
    return false;
  }

  @Override
  public @Nullable List<ProviderConfigProperty> getConfigProperties() {
    return CONFIG_PROPERTIES;
  }
}
