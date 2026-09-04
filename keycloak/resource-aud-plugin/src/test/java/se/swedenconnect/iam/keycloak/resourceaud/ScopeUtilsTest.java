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
package se.swedenconnect.iam.keycloak.resourceaud;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ScopeUtils}.
 *
 * @author Martin Lindström
 */
class ScopeUtilsTest {

  // ---------------------------------------------------------------------------
  // extractFunction
  // ---------------------------------------------------------------------------

  @Test
  void extractFunction_null() {
    assertNull(ScopeUtils.extractFunction(null));
  }

  @Test
  void extractFunction_blank() {
    assertNull(ScopeUtils.extractFunction("   "));
  }

  @Test
  void extractFunction_noOrgScope() {
    assertNull(ScopeUtils.extractFunction("openid profile"));
  }

  @Test
  void extractFunction_singleOrgScope() {
    assertEquals("demo", ScopeUtils.extractFunction("5590026042:demo:read"));
  }

  @Test
  void extractFunction_mixedScopes_returnsFirst() {
    assertEquals("demo", ScopeUtils.extractFunction("openid 5590026042:demo:read profile"));
  }

  // ---------------------------------------------------------------------------
  // extractAllFunctions
  // ---------------------------------------------------------------------------

  @Test
  void extractAllFunctions_null() {
    assertTrue(ScopeUtils.extractAllFunctions(null).isEmpty());
  }

  @Test
  void extractAllFunctions_blank() {
    assertTrue(ScopeUtils.extractAllFunctions("   ").isEmpty());
  }

  @Test
  void extractAllFunctions_noOrgScope() {
    assertTrue(ScopeUtils.extractAllFunctions("openid profile").isEmpty());
  }

  @Test
  void extractAllFunctions_singleFunction() {
    assertEquals(Set.of("demo"), ScopeUtils.extractAllFunctions("5590026042:demo:read"));
  }

  @Test
  void extractAllFunctions_twoDistinctFunctions() {
    final var result = ScopeUtils.extractAllFunctions(
        "5590026042:demo:read 5591617864:walletreg:write");
    assertEquals(Set.of("demo", "walletreg"), result);
  }

  @Test
  void extractAllFunctions_deduplication() {
    final var result = ScopeUtils.extractAllFunctions(
        "5590026042:demo:read 5591617864:demo:write");
    assertEquals(Set.of("demo"), result);
    assertEquals(1, result.size());
  }

  @Test
  void extractAllFunctions_mixedScopes() {
    final var result = ScopeUtils.extractAllFunctions(
        "openid 5590026042:demo:read profile 5591617864:walletreg:write");
    assertEquals(Set.of("demo", "walletreg"), result);
  }

  // ---------------------------------------------------------------------------
  // parseOrgScopes
  // ---------------------------------------------------------------------------

  @Test
  void parseOrgScopes_null_isEmpty() {
    assertTrue(ScopeUtils.parseOrgScopes(null).isEmpty());
  }

  @Test
  void parseOrgScopes_blank_isEmpty() {
    assertTrue(ScopeUtils.parseOrgScopes("   ").isEmpty());
  }

  @Test
  void parseOrgScopes_splitsIntoParts() {
    final List<ScopeUtils.OrgScope> scopes = ScopeUtils.parseOrgScopes("5590026042:demo:write");
    assertEquals(1, scopes.size());
    final ScopeUtils.OrgScope scope = scopes.get(0);
    assertEquals("5590026042:demo:write", scope.raw());
    assertEquals("5590026042", scope.organizationIdentifier());
    assertEquals("demo", scope.function());
    assertEquals("write", scope.right());
  }

  @Test
  void parseOrgScopes_ignoresPlainOidcScopes() {
    final List<ScopeUtils.OrgScope> scopes = ScopeUtils.parseOrgScopes(
        "openid profile 5590026042:demo:read https://id.oidc.se/scope/naturalPersonNumber");
    assertEquals(List.of("5590026042:demo:read"), scopes.stream().map(ScopeUtils.OrgScope::raw).toList());
  }

  @Test
  void parseOrgScopes_ignoresUnknownRightLevel() {
    assertTrue(ScopeUtils.parseOrgScopes("5590026042:demo:delete").isEmpty());
  }

  @Test
  void parseOrgScopes_ignoresBlankSegments() {
    assertTrue(ScopeUtils.parseOrgScopes(":demo:read").isEmpty());
    assertTrue(ScopeUtils.parseOrgScopes("5590026042::read").isEmpty());
  }

  @Test
  void parseOrgScopes_keepsRequestOrder() {
    final List<ScopeUtils.OrgScope> scopes = ScopeUtils.parseOrgScopes(
        "5591617864:walletreg:admin 5590026042:demo:read");
    assertEquals(List.of("5591617864:walletreg:admin", "5590026042:demo:read"),
        scopes.stream().map(ScopeUtils.OrgScope::raw).toList());
  }

  // ---------------------------------------------------------------------------
  // qualifyingGroupPaths
  // ---------------------------------------------------------------------------

  @Test
  void qualifyingGroupPaths_read_acceptsEveryLevel() {
    assertEquals(
        Set.of("/orgs/5590026042/_read", "/orgs/5590026042/_write", "/orgs/5590026042/_admin",
            "/orgs/5590026042/demo/_read", "/orgs/5590026042/demo/_write",
            "/orgs/5590026042/demo/_admin"),
        ScopeUtils.qualifyingGroupPaths("5590026042", "demo", "read"));
  }

  @Test
  void qualifyingGroupPaths_write_excludesRead() {
    assertEquals(
        Set.of("/orgs/5590026042/_write", "/orgs/5590026042/_admin",
            "/orgs/5590026042/demo/_write", "/orgs/5590026042/demo/_admin"),
        ScopeUtils.qualifyingGroupPaths("5590026042", "demo", "write"));
  }

  @Test
  void qualifyingGroupPaths_admin_acceptsAdminOnly() {
    assertEquals(
        Set.of("/orgs/5590026042/_admin", "/orgs/5590026042/demo/_admin"),
        ScopeUtils.qualifyingGroupPaths("5590026042", "demo", "admin"));
  }

  @Test
  void qualifyingGroupPaths_unknownRight_isEmpty() {
    assertTrue(ScopeUtils.qualifyingGroupPaths("5590026042", "demo", "delete").isEmpty());
  }

}
