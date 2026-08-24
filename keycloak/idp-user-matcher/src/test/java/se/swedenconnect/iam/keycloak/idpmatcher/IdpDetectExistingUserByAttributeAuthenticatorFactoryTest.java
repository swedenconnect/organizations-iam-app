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

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IdpDetectExistingUserByAttributeAuthenticatorFactory}.
 *
 * @author Martin Lindström
 */
class IdpDetectExistingUserByAttributeAuthenticatorFactoryTest {

  private final IdpDetectExistingUserByAttributeAuthenticatorFactory factory =
      new IdpDetectExistingUserByAttributeAuthenticatorFactory();

  @Test
  void providerIdentityIsStable() {
    assertEquals("idp-detect-existing-user-by-attr", this.factory.getId());
    assertEquals("Detect Existing Broker User By Attribute", this.factory.getDisplayType());
  }

  /** The execution is the gate for unknown identities, so REQUIRED must be the only choice. */
  @Test
  void requirementChoicesAreRequiredOnly() {
    final AuthenticationExecutionModel.Requirement[] choices = this.factory.getRequirementChoices();

    assertEquals(1, choices.length);
    assertEquals(AuthenticationExecutionModel.Requirement.REQUIRED, choices[0]);
  }

  @Test
  void isConfigurableAndDisallowsUserSetup() {
    assertTrue(this.factory.isConfigurable());
    assertFalse(this.factory.isUserSetupAllowed());
  }

  @Test
  void exposesTheThreeConfigProperties() {
    final List<ProviderConfigProperty> properties = this.factory.getConfigProperties();

    assertEquals(
        List.of(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE,
            IdpDetectExistingUserByAttributeAuthenticatorFactory.REQUIRED_ROLE,
            IdpDetectExistingUserByAttributeAuthenticatorFactory.FORBIDDEN_ROLE),
        properties.stream().map(ProviderConfigProperty::getName).toList());

    assertTrue(properties.getFirst().isRequired(), "the match attribute must be required");
    assertFalse(properties.get(1).isRequired());
    assertFalse(properties.get(2).isRequired());
  }

  /** The authenticator is stateless, so the factory may hand out the same instance every time. */
  @Test
  void createReturnsTheStatelessAuthenticator() {
    assertInstanceOf(IdpDetectExistingUserByAttributeAuthenticator.class, this.factory.create(null));
    assertEquals(this.factory.create(null), this.factory.create(null));
  }
}
