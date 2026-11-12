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
package se.swedenconnect.iam.admin.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import se.swedenconnect.iam.admin.keycloak.model.UserProfileInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Tests that an attribute the application writes but the realm would discard is reported at
 * startup, which is what left phone numbers unstored without any error, see
 * <a href="https://github.com/swedenconnect/organizations-iam-app/issues/185">issue #185</a>.
 *
 * @author Per Fredrik Plars
 */
@ExtendWith(MockitoExtension.class)
class UserAttributeDeclarationCheckTest {

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  private UserAttributeDeclarationCheck check;

  @BeforeEach
  void setUp() {
    this.check = new UserAttributeDeclarationCheck(this.keycloakAdminClient);
  }

  /** A realm declaring every attribute the application writes has nothing to report. */
  @Test
  void allAttributesDeclared_reportsNothing() {
    assertThat(this.check.report(profile(KeycloakAdminClient.WRITTEN_USER_ATTRIBUTES, null)))
        .isEmpty();
  }

  /** The realm as issue #185 found it: everything but the phone number. */
  @Test
  void phoneNumberMissing_isReported() {
    final Set<String> declared = KeycloakAdminClient.WRITTEN_USER_ATTRIBUTES.stream()
        .filter(name -> !"phoneNumber".equals(name))
        .collect(Collectors.toUnmodifiableSet());

    assertThat(this.check.report(profile(declared, null))).containsExactly("phoneNumber");
  }

  /** A realm declaring none of them reports all of them, in a stable order. */
  @Test
  void nothingDeclared_reportsEveryAttributeSorted() {
    final List<String> reported = this.check.report(profile(Set.of(), null));

    assertThat(reported)
        .containsExactlyInAnyOrderElementsOf(KeycloakAdminClient.WRITTEN_USER_ATTRIBUTES)
        .isSorted();
  }

  /** An unmanaged attribute policy that lets an admin write keeps the attributes, undeclared. */
  @Test
  void unmanagedAttributesAllowed_reportsNothing() {
    assertThat(this.check.report(profile(Set.of(), "ADMIN_EDIT"))).isEmpty();
    assertThat(this.check.report(profile(Set.of(), "enabled"))).isEmpty();
  }

  /** A policy that only lets an admin view does not help a write. */
  @Test
  void unmanagedAttributesViewOnly_isReported() {
    assertThat(this.check.report(profile(Set.of(), "ADMIN_VIEW")))
        .containsExactlyInAnyOrderElementsOf(KeycloakAdminClient.WRITTEN_USER_ATTRIBUTES);
  }

  /** A Keycloak that cannot be reached leaves a warning, not a failed startup. */
  @Test
  void keycloakUnreachable_doesNotFailStartup() {
    when(this.keycloakAdminClient.fetchUserProfile())
        .thenThrow(new KeycloakAdminException("Keycloak admin API call failed for GET /users/profile"));

    assertThatCode(() -> this.check.checkWrittenAttributes()).doesNotThrowAnyException();
  }

  /** The profile is read once the application is up, and reported on. */
  @Test
  void onStartup_theProfileIsRead() {
    when(this.keycloakAdminClient.fetchUserProfile())
        .thenReturn(profile(KeycloakAdminClient.WRITTEN_USER_ATTRIBUTES, null));

    assertThatCode(() -> this.check.checkWrittenAttributes()).doesNotThrowAnyException();
  }

  /** The representation Keycloak answers with, as it reaches the client. */
  @Test
  void userProfileOf_readsTheRepresentationKeycloakSends() {
    final UserProfileInfo profile = KeycloakAdminClient.userProfileOf(Map.of(
        "attributes", List.of(
            Map.of("name", "username", "permissions", Map.of("view", List.of("admin"))),
            Map.of("name", "personalIdentityNumber"),
            Map.of("displayName", "an entry without a name")),
        "unmanagedAttributePolicy", "ADMIN_EDIT"));

    assertThat(profile.declaredAttributes()).containsExactly("username", "personalIdentityNumber");
    assertThat(profile.unmanagedAttributePolicy()).isEqualTo("ADMIN_EDIT");
    assertThat(profile.keepsUndeclaredAttributes()).isTrue();
  }

  /** A realm that sets no policy sends none, which is what discards an undeclared attribute. */
  @Test
  void userProfileOf_absentPolicyMeansNoPolicy() {
    final UserProfileInfo profile = KeycloakAdminClient.userProfileOf(
        Map.of("attributes", List.of(Map.of("name", "email"))));

    assertThat(profile.unmanagedAttributePolicy()).isNull();
    assertThat(profile.keepsUndeclaredAttributes()).isFalse();
    assertThat(profile.discarded(Set.of("phoneNumber", "email"))).containsExactly("phoneNumber");
  }

  /** No representation at all is an error, not an empty profile. */
  @Test
  void userProfileOf_noRepresentation_fails() {
    assertThatThrownBy(() -> KeycloakAdminClient.userProfileOf(null))
        .isInstanceOf(KeycloakAdminException.class);
  }

  private static UserProfileInfo profile(final Set<String> declared, final String policy) {
    return new UserProfileInfo(declared, policy);
  }

}
