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

  /** Blank entries at the start, in the middle and at the end are skipped, and the order is kept. */
  @Test
  void fromConfig_blankEntriesAreSkipped() {
    final java.util.List<MatchRule> expected = java.util.List.of(
        new MatchRule("efosId", "superuser", null), new MatchRule("personalIdentityNumber", null, null));

    assertEquals(expected, fromList("##efosId;requiredRole=superuser##personalIdentityNumber"));
    assertEquals(expected, fromList("efosId;requiredRole=superuser####personalIdentityNumber"));
    assertEquals(expected, fromList("efosId;requiredRole=superuser##personalIdentityNumber##"));
    assertEquals(expected, fromList("##efosId;requiredRole=superuser## ##personalIdentityNumber##"));
  }

  /** An entry holding only whitespace names no attribute and is skipped like an empty one. */
  @Test
  void fromConfig_whitespaceOnlyEntriesAreSkipped() {
    assertEquals(java.util.List.of(new MatchRule("efosId", null, null)),
        MatchRule.fromConfig(config(Map.of("matchAttribute", "  ##efosId##\t## "))));
  }

  /** A list of blank entries only is reported as a list with no entries. */
  @Test
  void fromConfig_allBlankIsAMissingList() {
    for (final String list : java.util.List.of("##", "####", " ", " ## \t")) {
      final MatchRule.InvalidMatchConfigurationException e = assertThrows(
          MatchRule.InvalidMatchConfigurationException.class, () -> fromList(list));
      assertTrue(e.getMessage().contains("missing or has no entries"), e.getMessage());
    }
  }

  /** The position in an error counts the blank entries before the malformed one. */
  @Test
  void fromConfig_errorPositionCountsBlankEntries() {
    final MatchRule.InvalidMatchConfigurationException e = assertThrows(
        MatchRule.InvalidMatchConfigurationException.class,
        () -> fromList("##efosId## ##personalIdentityNumber;role=x"));
    assertTrue(e.getMessage().contains("entry 4"), e.getMessage());
  }

  /** Spaces around an attribute name in an entry that names one are still rejected. */
  @Test
  void fromConfig_spacesAroundANameAreStillRejected() {
    final MatchRule.InvalidMatchConfigurationException e = assertThrows(
        MatchRule.InvalidMatchConfigurationException.class, () -> fromList("## efosId"));
    assertTrue(e.getMessage().contains("entry 2"), e.getMessage());
    assertTrue(e.getMessage().contains("spaces"), e.getMessage());
  }

  /** An entry with roles but no attribute name is not blank, and is still rejected. */
  @Test
  void fromConfig_rolesWithoutAnAttributeAreStillRejected() {
    final MatchRule.InvalidMatchConfigurationException e = assertThrows(
        MatchRule.InvalidMatchConfigurationException.class,
        () -> fromList("efosId##;requiredRole=superuser"));
    assertTrue(e.getMessage().contains("entry 2"), e.getMessage());
    assertTrue(e.getMessage().contains("no attribute name"), e.getMessage());
  }

  /** A duplicate separated by a blank entry is still a duplicate. */
  @Test
  void fromConfig_duplicateAcrossABlankEntry() {
    final MatchRule.InvalidMatchConfigurationException e = assertThrows(
        MatchRule.InvalidMatchConfigurationException.class, () -> fromList("efosId####efosId"));
    assertTrue(e.getMessage().contains("more than once"), e.getMessage());
  }

  private static java.util.List<MatchRule> fromList(final String list) {
    return MatchRule.fromConfig(config(Map.of("matchAttribute", list)));
  }

  private static AuthenticatorConfigModel config(final Map<String, String> values) {
    final AuthenticatorConfigModel model = new AuthenticatorConfigModel();
    model.setConfig(values);
    return model;
  }
}
