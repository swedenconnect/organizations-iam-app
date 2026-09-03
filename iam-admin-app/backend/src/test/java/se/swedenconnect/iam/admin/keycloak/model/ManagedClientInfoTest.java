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
package se.swedenconnect.iam.admin.keycloak.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test cases for {@link ManagedClientInfo}.
 *
 * @author Felix Hellman
 */
class ManagedClientInfoTest {

  @Test
  @DisplayName("parseFunctions splits on comma and trims whitespace")
  void parseFunctionsSplitsAndTrims() {
    assertEquals(Set.of("demo", "walletreg"), ManagedClientInfo.parseFunctions("demo,walletreg"));
    assertEquals(Set.of("demo", "walletreg"), ManagedClientInfo.parseFunctions(" demo , walletreg "));
  }

  @Test
  @DisplayName("parseFunctions discards blank entries")
  void parseFunctionsDiscardsBlanks() {
    assertEquals(Set.of("demo"), ManagedClientInfo.parseFunctions("demo,,  ,"));
  }

  @Test
  @DisplayName("parseFunctions yields an empty set for a null or blank attribute")
  void parseFunctionsHandlesAbsentAttribute() {
    assertTrue(ManagedClientInfo.parseFunctions(null).isEmpty());
    assertTrue(ManagedClientInfo.parseFunctions("").isEmpty());
    assertTrue(ManagedClientInfo.parseFunctions("   ").isEmpty());
  }

  @Test
  @DisplayName("A client handles the functions it declares, and nothing else")
  void handlesDeclaredFunctionsOnly() {
    final ManagedClientInfo client = client(Set.of("demo", "walletreg"));

    assertTrue(client.handles("demo"));
    assertTrue(client.handles("walletreg"));
    assertFalse(client.handles("sweden-connect"));
    assertFalse(client.unscoped());
  }

  @Test
  @DisplayName("A client without client_functions handles no function at all")
  void unscopedClientHandlesNothing() {
    final ManagedClientInfo client = client(Set.of());

    assertFalse(client.handles("demo"));
    assertFalse(client.handles("sweden-connect"));
    assertTrue(client.unscoped());
  }

  private static ManagedClientInfo client(final Set<String> functions) {
    return new ManagedClientInfo(
        "b8f1c0e2-0000-0000-0000-000000000001", "https://demo-app.example.se", "Demo Application",
        true, false, functions,
        List.of("https://demo-app.example.se/login/oauth2/code/orgiam"),
        "https://demo-app.example.se/jwks", null, false, true);
  }
}
