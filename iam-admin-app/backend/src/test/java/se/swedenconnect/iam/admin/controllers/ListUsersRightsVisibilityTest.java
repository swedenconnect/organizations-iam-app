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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.UserPageResponse;
import se.swedenconnect.iam.admin.controllers.dto.UserResponse;
import se.swedenconnect.iam.admin.controllers.dto.UserRightResponse;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

/**
 * Tests that {@code GET /api/users} discloses only the rights the caller is entitled to see: rights
 * in an organization the caller administers, and there only the organization-level rights and the
 * rights on the functions the caller administers.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListUsersRightsVisibilityTest {

  private static final String DIGG = "2021006883";
  private static final String ICA = "5565552014";
  private static final String IDSEC = "5566778899";

  private static final String WALLETREG = "walletreg";
  private static final String SIGNSERVICE = "signservice";

  private static final String KLARA = "klara-uuid";
  private static final String OTHER = "other-uuid";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private UserController controller;

  @BeforeEach
  void setUp() {
    this.controller = new UserController(this.keycloakAdminClient, new IamAdminProperties());
    when(this.request.getSession(false)).thenReturn(this.session);
  }

  // ---------------------------------------------------------------------------
  // Organization granularity
  // ---------------------------------------------------------------------------

  /**
   * Mattias administers ICA and IDsec. Klara holds rights in ICA and in Digg. Mattias sees Klara's
   * ICA right and no trace of Digg.
   */
  @Test
  void listUsers_rightInOrgTheCallerDoesNotAdminister_isNotDisclosed() {
    this.setupSession(Set.of(ICA, IDSEC),
        orgEntry(ICA, "admin"),
        orgEntry(IDSEC, "admin"));
    this.setupCandidates(KLARA);
    this.setupRights(KLARA,
        new UserRight(ICA, null, "admin"),
        new UserRight(DIGG, null, "admin"));

    final UserPageResponse body = this.listUsers();

    assertThat(body.totalElements()).isEqualTo(1);
    assertThat(body.content()).hasSize(1);
    assertThat(body.content().getFirst().rights())
        .extracting(UserRightResponse::orgIdentifier)
        .containsExactly(ICA);
  }

  // ---------------------------------------------------------------------------
  // Function granularity
  // ---------------------------------------------------------------------------

  /**
   * A caller who administers a single function in an organization sees that function's rights and
   * the organization-level rights, but not the rights on the organization's other functions.
   */
  @Test
  void listUsers_functionAdmin_seesOwnFunctionAndOrgLevelRightsOnly() {
    this.setupSession(Set.of(ICA),
        orgEntry(ICA, null, new OrgRightsClaim.FunctionEntry(WALLETREG, "admin")));
    this.setupCandidates(KLARA);
    this.setupRights(KLARA,
        new UserRight(ICA, null, "read"),
        new UserRight(ICA, WALLETREG, "write"),
        new UserRight(ICA, SIGNSERVICE, "admin"));

    final UserPageResponse body = this.listUsers();

    assertThat(body.content()).hasSize(1);
    assertThat(body.content().getFirst().rights())
        .extracting(UserRightResponse::functionId)
        .containsExactlyInAnyOrder(null, WALLETREG);
  }

  /**
   * An organization-level admin right covers every function of the organization, so such a caller
   * sees all of its rights.
   */
  @Test
  void listUsers_orgLevelAdmin_seesEveryRightInThatOrganization() {
    this.setupSession(Set.of(ICA), orgEntry(ICA, "admin"));
    this.setupCandidates(KLARA);
    this.setupRights(KLARA,
        new UserRight(ICA, null, "read"),
        new UserRight(ICA, WALLETREG, "write"),
        new UserRight(ICA, SIGNSERVICE, "admin"));

    final UserPageResponse body = this.listUsers();

    assertThat(body.content().getFirst().rights()).hasSize(3);
  }

  // ---------------------------------------------------------------------------
  // Users dropping out of the response
  // ---------------------------------------------------------------------------

  /**
   * A user whose every right is invisible to the caller is absent from the response altogether,
   * and the reported total counts only the users that remain. An entry with an empty rights section
   * would itself disclose that the person holds something the caller may not see.
   */
  @Test
  void listUsers_userWithNoVisibleRight_isDroppedAndNotCounted() {
    this.setupSession(Set.of(ICA),
        orgEntry(ICA, null, new OrgRightsClaim.FunctionEntry(WALLETREG, "admin")));
    this.setupCandidates(KLARA, OTHER);
    this.setupRights(KLARA, new UserRight(ICA, WALLETREG, "read"));
    this.setupRights(OTHER, new UserRight(ICA, SIGNSERVICE, "read"));

    final UserPageResponse body = this.listUsers();

    assertThat(body.totalElements()).isEqualTo(1);
    assertThat(body.totalPages()).isEqualTo(1);
    assertThat(body.content()).extracting(UserResponse::userId).containsExactly(KLARA);
  }

  // ---------------------------------------------------------------------------
  // Superuser
  // ---------------------------------------------------------------------------

  /** A superuser is unaffected and continues to receive every right of every user. */
  @Test
  void listUsers_superuser_seesEveryRight() {
    final AdminSessionData data = new AdminSessionData(true, null, null, List.of(), Set.of(),
        new OrgRightsClaim(true, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
    when(this.keycloakAdminClient.fetchUsersPaged(0, 50)).thenReturn(List.of(userInfo(KLARA)));
    when(this.keycloakAdminClient.fetchUserCount()).thenReturn(1);
    this.setupRights(KLARA,
        new UserRight(ICA, WALLETREG, "admin"),
        new UserRight(DIGG, null, "admin"));

    final UserPageResponse body = this.listUsers();

    assertThat(body.totalElements()).isEqualTo(1);
    assertThat(body.content().getFirst().rights())
        .extracting(UserRightResponse::orgIdentifier)
        .containsExactlyInAnyOrder(ICA, DIGG);
  }

  // ---------------------------------------------------------------------------
  // SSO constraints
  // ---------------------------------------------------------------------------

  /** A session carrying a function constraint is narrowed at least as much as it is today. */
  @Test
  void listUsers_functionConstraint_narrowsFurther() {
    final AdminSessionData data = new AdminSessionData(false, WALLETREG, null, List.of(),
        Set.of(ICA), new OrgRightsClaim(false, List.of(orgEntry(ICA, "admin"))));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
    this.setupCandidates(KLARA);
    this.setupRights(KLARA,
        new UserRight(ICA, null, "read"),
        new UserRight(ICA, WALLETREG, "write"),
        new UserRight(ICA, SIGNSERVICE, "admin"));

    final UserPageResponse body = this.listUsers();

    assertThat(body.content().getFirst().rights())
        .extracting(UserRightResponse::functionId)
        .containsExactlyInAnyOrder(null, WALLETREG);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private UserPageResponse listUsers() {
    final ResponseEntity<UserPageResponse> response = this.controller.listUsers(0, 50, this.request);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    final UserPageResponse body = response.getBody();
    assertThat(body).isNotNull();
    return body;
  }

  private void setupSession(final Set<String> adminOrgs, final OrgRightsClaim.OrgEntry... entries) {
    final AdminSessionData data = new AdminSessionData(false, null, null, List.of(), adminOrgs,
        new OrgRightsClaim(false, List.of(entries)));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }

  private void setupCandidates(final String... userIds) {
    when(this.keycloakAdminClient.fetchUserIdsForOrgs(anySet())).thenReturn(List.of(userIds));
    for (final String userId : userIds) {
      when(this.keycloakAdminClient.fetchUserById(userId)).thenReturn(Optional.of(userInfo(userId)));
    }
  }

  private void setupRights(final String userId, final UserRight... rights) {
    when(this.keycloakAdminClient.fetchUserRights(userId)).thenReturn(List.of(rights));
  }

  private static UserInfo userInfo(final String userId) {
    return new UserInfo(userId, userId, "Klara", "Karlsson", null, null, null, null, false,
        List.of());
  }

  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId, final String orgLevelRight,
      final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(
        OrganizationID.of(orgId), "Test Org", new LocalizedString(), orgLevelRight,
        List.of(functions));
  }

}
