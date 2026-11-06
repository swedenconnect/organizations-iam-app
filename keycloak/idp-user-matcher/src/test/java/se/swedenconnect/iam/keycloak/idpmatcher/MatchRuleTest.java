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
import org.keycloak.models.AuthenticatorConfigModel;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MatchRule}, the entry format of the {@code matchAttribute} list.
 *
 * @author Martin Lindström
 */
class MatchRuleTest {

  /** An attribute name alone has no roles. */
  @Test
  void attributeOnly() {
    assertEquals(new MatchRule("efosId", null, null), MatchRule.parse("efosId", 1));
  }

  /** Either role, or both in any order. */
  @Test
  void roles() {
    assertEquals(new MatchRule("efosId", "superuser", null), MatchRule.parse("efosId;requiredRole=superuser", 1));
    assertEquals(new MatchRule("personalIdentityNumber", null, "superuser"),
        MatchRule.parse("personalIdentityNumber;forbiddenRole=superuser", 1));
    assertEquals(new MatchRule("efosId", "a", "b"), MatchRule.parse("efosId;forbiddenRole=b;requiredRole=a", 1));
  }

  /** A role name may contain an equals sign after the first one, and spaces inside it. */
  @Test
  void roleValueIsEverythingAfterTheFirstEqualsSign() {
    assertEquals(new MatchRule("efosId", "a=b c", null), MatchRule.parse("efosId;requiredRole=a=b c", 1));
  }

  /**
   * A value written before the setting became a list, a single attribute name, is a list of one,
   * and the entries keep their order.
   */
  @Test
  void fromConfig_singleValueIsAListOfOne_andOrderIsKept() {
    assertEquals(java.util.List.of(new MatchRule("personalIdentityNumber", null, null)),
        MatchRule.fromConfig(config(Map.of("matchAttribute", "personalIdentityNumber"))));
    assertEquals(java.util.List.of(new MatchRule("efosId", "superuser", null),
            new MatchRule("personalIdentityNumber", null, "superuser")),
        MatchRule.fromConfig(config(Map.of("matchAttribute",
            "efosId;requiredRole=superuser##personalIdentityNumber;forbiddenRole=superuser"))));
  }

  /** The error names the entry by position and never needs a login value. */
  @Test
  void errorNamesTheEntry() {
    final MatchRule.InvalidMatchConfigurationException e = assertThrows(
        MatchRule.InvalidMatchConfigurationException.class,
        () -> MatchRule.fromConfig(config(Map.of("matchAttribute", "personalIdentityNumber##efosId;role=x"))));
    assertTrue(e.getMessage().contains("entry 2"), e.getMessage());
  }

  private static AuthenticatorConfigModel config(final Map<String, String> values) {
    final AuthenticatorConfigModel model = new AuthenticatorConfigModel();
    model.setConfig(values);
    return model;
  }
}
