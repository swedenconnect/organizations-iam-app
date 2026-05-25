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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.RightsHolderEntry;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code listRightsHolders} endpoint in
 * {@link IamServiceOrganizationController}.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
class ListRightsHoldersControllerTest {

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  private final OrgRightsClaimParser claimParser = new OrgRightsClaimParser();

  private IamServiceOrganizationController controller;

  private static final String ORG = "5590026042";
  private static final String FUNC = "demo";

  @BeforeEach
  void setUp() {
    this.controller = new IamServiceOrganizationController(this.keycloakAdminClient, this.claimParser);
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  // -----------------------------------------------------------------------
  // 200 — superuser
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_superuser_returnsOk() {
    setupSuperuserAuth();
    when(this.keycloakAdminClient.organizationExists(ORG)).thenReturn(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG, FUNC)).thenReturn(true);
    when(this.keycloakAdminClient.fetchFunctionRightsHolders(ORG, FUNC)).thenReturn(List.of(
        new RightsHolderEntry("u1", "197001011234", "Anna Andersson", "admin", "function")));

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC, superuserJwt());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isInstanceOf(IamServiceOrganizationController.RightsHoldersResponse.class);
    final var body = (IamServiceOrganizationController.RightsHoldersResponse) response.getBody();
    assertThat(body.users()).hasSize(1);
    assertThat(body.users().getFirst().userId()).isEqualTo("u1");
  }

  // -----------------------------------------------------------------------
  // 200 — function admin (exact match)
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_functionAdmin_returnsOk() {
    setupNonSuperuserAuth(orgRightsClaimRaw(ORG, FUNC, "admin"));
    when(this.keycloakAdminClient.organizationExists(ORG)).thenReturn(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG, FUNC)).thenReturn(true);
    when(this.keycloakAdminClient.fetchFunctionRightsHolders(ORG, FUNC)).thenReturn(List.of());

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC,
        jwtWithOrgRights(orgRightsClaimRaw(ORG, FUNC, "admin")));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  // -----------------------------------------------------------------------
  // 200 — org-wide admin (wildcard)
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_orgWideAdmin_returnsOk() {
    setupNonSuperuserAuth(orgRightsClaimRaw(ORG, "*", "admin"));
    when(this.keycloakAdminClient.organizationExists(ORG)).thenReturn(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG, FUNC)).thenReturn(true);
    when(this.keycloakAdminClient.fetchFunctionRightsHolders(ORG, FUNC)).thenReturn(List.of());

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC,
        jwtWithOrgRights(orgRightsClaimRaw(ORG, "*", "admin")));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  // -----------------------------------------------------------------------
  // 403 — admin on a different org
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_differentOrg_returns403() {
    final Object claim = orgRightsClaimRaw("9999999999", FUNC, "admin");
    setupNonSuperuserAuth(claim);

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC,
        jwtWithOrgRights(claim));

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).organizationExists(anyString());
    verify(this.keycloakAdminClient, never()).fetchFunctionRightsHolders(anyString(), anyString());
  }

  // -----------------------------------------------------------------------
  // 403 — non-admin right (write) on correct org/function
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_writeRight_returns403() {
    final Object claim = orgRightsClaimRaw(ORG, FUNC, "write");
    setupNonSuperuserAuth(claim);

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC,
        jwtWithOrgRights(claim));

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).organizationExists(anyString());
  }

  // -----------------------------------------------------------------------
  // 403 — non-admin right (read) on correct org/function
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_readRight_returns403() {
    final Object claim = orgRightsClaimRaw(ORG, FUNC, "read");
    setupNonSuperuserAuth(claim);

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC,
        jwtWithOrgRights(claim));

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).organizationExists(anyString());
  }

  // -----------------------------------------------------------------------
  // 403 — no org_rights claim at all (non-superuser)
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_noOrgRightsClaim_returns403() {
    setupNonSuperuserAuth(null);

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC,
        jwtWithOrgRights(null));

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).organizationExists(anyString());
  }

  // -----------------------------------------------------------------------
  // 404 — unknown org
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_unknownOrg_returns404() {
    setupSuperuserAuth();
    when(this.keycloakAdminClient.organizationExists(ORG)).thenReturn(false);

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC, superuserJwt());

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  // -----------------------------------------------------------------------
  // 404 — function not attached to org
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_functionNotAttached_returns404() {
    setupSuperuserAuth();
    when(this.keycloakAdminClient.organizationExists(ORG)).thenReturn(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG, FUNC)).thenReturn(false);

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC, superuserJwt());

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  // -----------------------------------------------------------------------
  // 500 — Keycloak error
  // -----------------------------------------------------------------------

  @Test
  void listRightsHolders_keycloakError_returns500() {
    setupSuperuserAuth();
    when(this.keycloakAdminClient.organizationExists(ORG)).thenReturn(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG, FUNC)).thenReturn(true);
    when(this.keycloakAdminClient.fetchFunctionRightsHolders(ORG, FUNC))
        .thenThrow(new KeycloakAdminException("Connection refused"));

    final ResponseEntity<?> response = this.controller.listRightsHolders(ORG, FUNC, superuserJwt());

    assertThat(response.getStatusCode().value()).isEqualTo(500);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private void setupSuperuserAuth() {
    SecurityContextHolder.getContext().setAuthentication(
        new JwtAuthenticationToken(superuserJwt(),
            List.of(new SimpleGrantedAuthority("ROLE_SUPERUSER"))));
  }

  private void setupNonSuperuserAuth(final Object orgRightsClaim) {
    SecurityContextHolder.getContext().setAuthentication(
        new JwtAuthenticationToken(jwtWithOrgRights(orgRightsClaim), List.of()));
  }

  private static Jwt superuserJwt() {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .claim("sub", "superuser-id")
        .claim("realm_access", Map.of("roles", List.of("superuser")))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .build();
  }

  private static Jwt jwtWithOrgRights(final Object orgRightsClaim) {
    final var builder = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .claim("sub", "user-id")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600));
    if (orgRightsClaim != null) {
      builder.claim("org_rights", orgRightsClaim);
    }
    return builder.build();
  }

  /**
   * Builds a raw {@code org_rights} claim value matching the structure produced by the
   * Keycloak protocol mapper.
   */
  private static Object orgRightsClaimRaw(
      final String orgId, final String function, final String right) {
    return List.of(Map.of(
        "organization_identifier", orgId,
        "organization_name#sv", "Test Org",
        "functions", List.of(Map.of("function", function, "right", right))));
  }

}
