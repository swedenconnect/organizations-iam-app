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
package se.swedenconnect.iam.admin.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.service.model.ReconciliationPlan;
import se.swedenconnect.iam.admin.service.model.ReconciliationTarget;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test cases for {@link ClientReconciliationService#plan(List, Map)}.
 *
 * @author Felix Hellman
 */
class ClientReconciliationPlanTest {

  private static final Map<String, Set<String>> TOPOLOGY = Map.of(
      "SE5561234567", Set.of("demo", "walletreg"),
      "SE5569876543", Set.of("demo"));

  @Test
  @DisplayName("A client is planned for every org that has one of its functions attached")
  void plansEveryOrgWithAHandledFunction() {
    final ReconciliationPlan plan =
        ClientReconciliationService.plan(List.of(client("demo-app", Set.of("demo"))), TOPOLOGY);

    assertEquals(Set.of(
            target("demo-app", "SE5561234567", "demo"),
            target("demo-app", "SE5569876543", "demo")),
        Set.copyOf(plan.ensure()));
    assertEquals(1, plan.clientCount());
  }

  @Test
  @DisplayName("Functions the client does not handle are left out of the ensure set")
  void skipsUnhandledFunctions() {
    final ReconciliationPlan plan =
        ClientReconciliationService.plan(List.of(client("demo-app", Set.of("walletreg"))), TOPOLOGY);

    assertEquals(List.of(target("demo-app", "SE5561234567", "walletreg")), plan.ensure());
  }

  @Test
  @DisplayName("A client without client_functions is planned for nothing, and loses every artifact")
  void unscopedClientCoversNothing() {
    final ReconciliationPlan plan =
        ClientReconciliationService.plan(List.of(client("unscoped-app", Set.of())), TOPOLOGY);

    assertTrue(plan.ensure().isEmpty());
    assertEquals(3, plan.remove().size());
  }

  @Test
  @DisplayName("Removals are planned for the combinations the client no longer handles")
  void plansRemovals() {
    final ReconciliationPlan plan =
        ClientReconciliationService.plan(List.of(client("demo-app", Set.of("demo"))), TOPOLOGY);

    assertEquals(Set.of(
            target("demo-app", "SE5561234567", "demo"),
            target("demo-app", "SE5569876543", "demo")),
        Set.copyOf(plan.ensure()));
    assertEquals(List.of(target("demo-app", "SE5561234567", "walletreg")), plan.remove());
  }

  @Test
  @DisplayName("An empty topology yields an empty plan")
  void emptyTopologyYieldsEmptyPlan() {
    final ReconciliationPlan plan =
        ClientReconciliationService.plan(List.of(client("demo-app", Set.of("demo"))), Map.of());

    assertTrue(plan.isEmpty());
    assertEquals(0, plan.clientCount());
  }

  @Test
  @DisplayName("Every managed client is covered")
  void coversEveryClient() {
    final ReconciliationPlan plan = ClientReconciliationService.plan(
        List.of(client("demo-app", Set.of("demo")), client("wallet-app", Set.of("walletreg"))),
        TOPOLOGY);

    assertEquals(2, plan.clientCount());
    assertEquals(3, plan.ensure().size());
  }

  private static ManagedClientInfo client(final String name, final Set<String> functions) {
    return new ManagedClientInfo("uuid-" + name, "https://" + name + ".example.se", name,
        true, false, functions,
        List.of("https://" + name + ".example.se/login/oauth2/code/orgiam"),
        "https://" + name + ".example.se/jwks", null, false, true, true, true);
  }

  private static ReconciliationTarget target(
      final String name, final String orgIdentifier, final String functionId) {
    return new ReconciliationTarget(
        "uuid-" + name, "https://" + name + ".example.se", orgIdentifier, functionId);
  }
}
