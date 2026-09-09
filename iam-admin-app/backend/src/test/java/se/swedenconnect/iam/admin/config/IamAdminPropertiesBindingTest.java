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
package se.swedenconnect.iam.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the binding of {@link IamAdminProperties}.
 *
 * @author Martin Lindström
 */
class IamAdminPropertiesBindingTest {

  /**
   * The removed {@code authz-client-ids} setting is an unknown property, and an unknown property
   * under {@code iam.admin} is ignored. A deployment whose configuration still sets it starts, and
   * the setting has no effect.
   */
  @Test
  void removedAuthzClientIds_isIgnored() {
    final ConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
        "iam.admin.admin-api-base", "https://keycloak.example.com/admin/realms/orgiam",
        "iam.admin.authz-client-ids[0]", "https://demo-app.example.se"));

    final IamAdminProperties properties = new Binder(source)
        .bind("iam.admin", IamAdminProperties.class)
        .get();

    assertThat(properties.getAdminApiBase())
        .isEqualTo("https://keycloak.example.com/admin/realms/orgiam");
  }

  /** Without any user-registration settings, the defaults apply. */
  @Test
  void userRegistration_defaults() throws Exception {
    final IamAdminProperties properties = bind(Map.of(
        "iam.admin.admin-api-base", "https://keycloak.example.com/admin/realms/orgiam"));
    properties.afterPropertiesSet();

    final IamAdminProperties.UserRegistration settings = properties.getUserRegistration();
    assertThat(settings.isAllowSelectUserId()).isFalse();
    assertThat(settings.isAllowTemporaryPassword()).isFalse();
    assertThat(settings.isEidAttributeRequired()).isTrue();
    assertThat(settings.isPersonalNumberEnabled()).isTrue();
    assertThat(settings.isHsaIdEnabled()).isFalse();
    assertThat(settings.isOrgAffiliationEnabled()).isFalse();
    assertThat(settings.isEfosIdEnabled()).isFalse();
  }

  /** Every setting in the block binds. */
  @Test
  void userRegistration_bindsAllSettings() throws Exception {
    final IamAdminProperties properties = bind(Map.ofEntries(
        Map.entry("iam.admin.user-registration.allow-select-user-id", "true"),
        Map.entry("iam.admin.user-registration.allow-temporary-password", "true"),
        Map.entry("iam.admin.user-registration.eid-attribute-required", "false"),
        Map.entry("iam.admin.user-registration.personal-number-enabled", "false"),
        Map.entry("iam.admin.user-registration.hsa-id-enabled", "true"),
        Map.entry("iam.admin.user-registration.org-affiliation-enabled", "true"),
        Map.entry("iam.admin.user-registration.efos-id-enabled", "true")));
    properties.afterPropertiesSet();

    final IamAdminProperties.UserRegistration settings = properties.getUserRegistration();
    assertThat(settings.isAllowSelectUserId()).isTrue();
    assertThat(settings.isAllowTemporaryPassword()).isTrue();
    assertThat(settings.isEidAttributeRequired()).isFalse();
    assertThat(settings.isPersonalNumberEnabled()).isFalse();
    assertThat(settings.isHsaIdEnabled()).isTrue();
    assertThat(settings.isOrgAffiliationEnabled()).isTrue();
    assertThat(settings.isEfosIdEnabled()).isTrue();
  }

  /** A temporary password without a selectable user ID is turned off rather than honoured. */
  @Test
  void userRegistration_temporaryPasswordWithoutSelectableUserId_isTurnedOff() throws Exception {
    final IamAdminProperties properties = bind(Map.of(
        "iam.admin.user-registration.allow-temporary-password", "true"));
    properties.afterPropertiesSet();

    assertThat(properties.getUserRegistration().isAllowTemporaryPassword()).isFalse();
  }

  /** Requiring an eID attribute while enabling none is a configuration error. */
  @Test
  void userRegistration_requiredButNoAttributeEnabled_fails() {
    final IamAdminProperties properties = bind(Map.of(
        "iam.admin.user-registration.eid-attribute-required", "true",
        "iam.admin.user-registration.personal-number-enabled", "false"));

    assertThatThrownBy(properties::afterPropertiesSet)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eid-attribute-required");
  }

  /**
   * The deprecated {@code pnr-userids} still works: it turns on the setting that replaced it, and
   * has no other effect.
   */
  @Test
  void deprecatedPnrUserids_mapsOntoAllowSelectUserId() throws Exception {
    final IamAdminProperties properties = bind(Map.of("iam.admin.pnr-userids", "true"));
    properties.afterPropertiesSet();

    assertThat(properties.isPnrUserids()).isTrue();
    assertThat(properties.getUserRegistration().isAllowSelectUserId()).isTrue();
    assertThat(properties.getUserRegistration().isPersonalNumberEnabled()).isTrue();
  }

  private static IamAdminProperties bind(final Map<String, String> values) {
    final ConfigurationPropertySource source = new MapConfigurationPropertySource(values);
    return new Binder(source).bind("iam.admin", IamAdminProperties.class).get();
  }
}
