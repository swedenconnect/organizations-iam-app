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
import se.swedenconnect.iam.admin.controllers.dto.AdminSessionResponse;
import se.swedenconnect.iam.admin.controllers.dto.UserRegistrationResponse;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The session response carries the user registration settings as one nested object, alongside the
 * flat booleans that were already there.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionUserRegistrationTest {

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private IamAdminProperties properties;

  private SessionController controller;

  @BeforeEach
  void setUp() {
    this.properties = new IamAdminProperties();
    this.controller = new SessionController(this.properties);

    when(this.request.getSession(false)).thenReturn(this.session);
    final AdminSessionData data = new AdminSessionData(true, null, null, List.of(), Set.of(),
        new OrgRightsClaim(true, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }

  /** The defaults are delivered as they stand. */
  @Test
  void session_carriesDefaultUserRegistrationSettings() {
    final ResponseEntity<AdminSessionResponse> response = this.controller.session(this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    final UserRegistrationResponse settings = response.getBody().userRegistration();
    assertThat(settings.allowSelectUserId()).isFalse();
    assertThat(settings.allowTemporaryPassword()).isFalse();
    assertThat(settings.eidAttributeRequired()).isTrue();
    assertThat(settings.personalNumberEnabled()).isTrue();
    assertThat(settings.hsaIdEnabled()).isFalse();
    assertThat(settings.orgAffiliationEnabled()).isFalse();
    assertThat(settings.efosIdEnabled()).isFalse();
  }

  /** Every configured setting reaches the frontend. */
  @Test
  void session_carriesConfiguredUserRegistrationSettings() {
    final IamAdminProperties.UserRegistration configured = this.properties.getUserRegistration();
    configured.setAllowSelectUserId(true);
    configured.setAllowTemporaryPassword(true);
    configured.setEidAttributeRequired(false);
    configured.setPersonalNumberEnabled(false);
    configured.setHsaIdEnabled(true);
    configured.setOrgAffiliationEnabled(true);
    configured.setEfosIdEnabled(true);

    final UserRegistrationResponse settings =
        this.controller.session(this.request).getBody().userRegistration();

    assertThat(settings).isEqualTo(
        new UserRegistrationResponse(true, true, false, false, true, true, true));
  }

  /** The flat booleans are unchanged by the addition. */
  @Test
  void session_keepsFlatBooleans() {
    this.properties.setAllowFunctionRemoval(true);
    this.properties.setAllowOrgRights(false);
    this.properties.setAllowAdminAssigningAdmin(true);

    final AdminSessionResponse body = this.controller.session(this.request).getBody();

    assertThat(body.allowFunctionRemoval()).isTrue();
    assertThat(body.allowOrgRights()).isFalse();
    assertThat(body.allowAdminAssigningAdmin()).isTrue();
    assertThat(body.superuser()).isTrue();
  }

}
