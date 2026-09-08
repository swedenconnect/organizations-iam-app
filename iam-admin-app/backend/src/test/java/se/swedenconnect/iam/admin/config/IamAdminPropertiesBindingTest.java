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
}
