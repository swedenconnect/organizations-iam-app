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
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.service.OrganizationService;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that attaching or detaching a function refreshes the organization cache entry.
 *
 * <p>The organizations view renders {@code attachedFunctions} off the cached
 * {@code OrganizationInfo}, and attaching a function writes the group tree in KeyCloak without
 * going through {@code OrganizationService}. Without the refresh the cached entry keeps the
 * function list it had when the organization was created, so a freshly attached function stays
 * invisible in the list view for the lifetime of the application while the user rights view, which
 * reads KeyCloak directly, shows it.</p>
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationFunctionCacheRefreshTest {

  private static final String ORG_ID = "5591617864";

  private static final String FUNCTION_ID = "demo";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private OrganizationService organizationService;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private OrganizationFunctionController controller;

  @BeforeEach
  void setUp() {
    this.controller = new OrganizationFunctionController(
        this.keycloakAdminClient, this.organizationService);
    when(this.request.getSession(false)).thenReturn(this.session);
  }

  @Test
  @DisplayName("Attaching a function refreshes the cached organization")
  void attachRefreshesTheCache() {
    setupSession(true);
    when(this.keycloakAdminClient.organizationExists(ORG_ID)).thenReturn(true);
    when(this.keycloakAdminClient.functionExists(FUNCTION_ID)).thenReturn(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG_ID, FUNCTION_ID)).thenReturn(false);

    assertThat(this.controller.attachFunction(ORG_ID, FUNCTION_ID, this.request)
        .getStatusCode().value()).isEqualTo(204);

    verify(this.keycloakAdminClient).attachFunctionToOrg(ORG_ID, FUNCTION_ID);
    verify(this.organizationService).refresh(ORG_ID);
  }

  @Test
  @DisplayName("Detaching a function refreshes the cached organization")
  void detachRefreshesTheCache() {
    setupSession(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG_ID, FUNCTION_ID)).thenReturn(true);

    assertThat(this.controller.detachFunction(ORG_ID, FUNCTION_ID, this.request)
        .getStatusCode().value()).isEqualTo(204);

    verify(this.keycloakAdminClient).detachFunctionFromOrg(ORG_ID, FUNCTION_ID);
    verify(this.organizationService).refresh(ORG_ID);
  }

  @Test
  @DisplayName("A failed attach leaves the cache alone")
  void failedAttachDoesNotRefresh() {
    setupSession(true);
    when(this.keycloakAdminClient.organizationExists(ORG_ID)).thenReturn(true);
    when(this.keycloakAdminClient.functionExists(FUNCTION_ID)).thenReturn(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG_ID, FUNCTION_ID)).thenReturn(false);
    doThrow(new KeycloakAdminException("Connection refused"))
        .when(this.keycloakAdminClient).attachFunctionToOrg(ORG_ID, FUNCTION_ID);

    assertThat(this.controller.attachFunction(ORG_ID, FUNCTION_ID, this.request)
        .getStatusCode().value()).isEqualTo(500);

    verify(this.organizationService, never()).refresh(anyString());
  }

  @Test
  @DisplayName("A rejected attach leaves the cache alone")
  void nonSuperuserDoesNotRefresh() {
    setupSession(false);

    assertThat(this.controller.attachFunction(ORG_ID, FUNCTION_ID, this.request)
        .getStatusCode().value()).isEqualTo(403);

    verify(this.organizationService, never()).refresh(anyString());
  }

  private void setupSession(final boolean superuser) {
    final AdminSessionData data = new AdminSessionData(
        superuser, null, null, List.of(), Set.of(),
        new OrgRightsClaim(superuser, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }
}
