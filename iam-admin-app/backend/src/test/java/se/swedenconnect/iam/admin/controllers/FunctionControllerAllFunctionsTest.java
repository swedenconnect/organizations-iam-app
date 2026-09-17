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
package se.swedenconnect.iam.admin.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.CreateFunctionRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that creating a function writes it into the {@code client_functions} of the clients marked
 * as handling all functions.
 *
 * <p>Such a client is a resource server for every function, including the ones not created yet.
 * This application reads the marker directly, but {@code resource-aud-plugin} runs inside KeyCloak
 * and validates the OAuth2 {@code resource} parameter against the raw attribute, so the attribute
 * has to name the new function as well.</p>
 *
 * @author Felix Hellman
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FunctionControllerAllFunctionsTest {

  private static final String FUNCTION_ID = "walletreg";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private FunctionController controller;

  @BeforeEach
  void setUp() {
    this.controller = new FunctionController(this.keycloakAdminClient, new IamAdminProperties());
    when(this.request.getSession(false)).thenReturn(this.session);
  }

  @Test
  @DisplayName("A created function is written to the all-functions clients")
  void createdFunctionIsMaterialized() {
    setupSession(true);
    when(this.keycloakAdminClient.functionExists(FUNCTION_ID)).thenReturn(false);
    when(this.keycloakAdminClient.materializeAllFunctions(FUNCTION_ID))
        .thenReturn(List.of("https://iam-admin.example.se"));

    assertThat(this.controller.createFunction(createRequest(), this.request)
        .getStatusCode().value()).isEqualTo(201);

    verify(this.keycloakAdminClient).materializeAllFunctions(FUNCTION_ID);
  }

  @Test
  @DisplayName("A KeyCloak failure while writing the attribute does not fail the creation")
  void materializationFailureDoesNotFailTheRequest() {
    setupSession(true);
    when(this.keycloakAdminClient.functionExists(FUNCTION_ID)).thenReturn(false);
    when(this.keycloakAdminClient.materializeAllFunctions(FUNCTION_ID))
        .thenThrow(new KeycloakAdminException("Connection refused"));

    assertThat(this.controller.createFunction(createRequest(), this.request)
        .getStatusCode().value()).isEqualTo(201);
  }

  @Test
  @DisplayName("Nothing is written when the function already exists")
  void existingFunctionIsNotMaterialized() {
    setupSession(true);
    when(this.keycloakAdminClient.functionExists(FUNCTION_ID)).thenReturn(true);

    assertThat(this.controller.createFunction(createRequest(), this.request)
        .getStatusCode().value()).isEqualTo(409);

    verify(this.keycloakAdminClient, never()).materializeAllFunctions(anyString());
  }

  @Test
  @DisplayName("Nothing is written when the caller is not a superuser")
  void nonSuperuserWritesNothing() {
    setupSession(false);

    assertThat(this.controller.createFunction(createRequest(), this.request)
        .getStatusCode().value()).isEqualTo(403);

    verify(this.keycloakAdminClient, never()).materializeAllFunctions(anyString());
  }

  private static CreateFunctionRequest createRequest() {
    return new CreateFunctionRequest(FUNCTION_ID, "Plånboksregistrering", "Wallet registration",
        null, null);
  }

  private void setupSession(final boolean superuser) {
    final AdminSessionData data = new AdminSessionData(
        superuser, null, null, List.of(), Set.of(),
        new OrgRightsClaim(superuser, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }
}
