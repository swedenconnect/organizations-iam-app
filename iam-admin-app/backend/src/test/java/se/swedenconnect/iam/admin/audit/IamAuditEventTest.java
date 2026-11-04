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
package se.swedenconnect.iam.admin.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.keycloak.model.RightHolder;
import se.swedenconnect.spring.audit.AuditEvent;
import se.swedenconnect.spring.audit.repository.JsonAuditEventMapper;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static se.swedenconnect.iam.admin.audit.RecordingAuditPublisher.object;

/**
 * Tests the shape of the audit events: the members each type carries, that members without a value
 * are left out, and how the principal and correlation ID are resolved.
 *
 * @author Martin Lindström
 */
class IamAuditEventTest {

  private final RecordingAuditPublisher publisher = new RecordingAuditPublisher();

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    CorrelationIDHolder.clear();
  }

  /** Every member of a created user that has a value is included, and nothing else. */
  @Test
  void userCreated_includesEveryMemberWithAValue() {
    this.publisher.publishEvent(IamAuditEvent.userCreated(
        new AuditData.User("u1", "Klara Karlsson", "198001011234", null, null, null, "klara", true), null));

    final AuditEvent event = this.publisher.single("user_created");
    assertThat(object(event, "user")).containsExactly(
        Map.entry("user_id", "u1"),
        Map.entry("display_name", "Klara Karlsson"),
        Map.entry("personal_identity_number", "198001011234"),
        Map.entry("username", "klara"),
        Map.entry("temporary_password_set", true));
    assertThat(event.getData()).containsOnlyKeys("user");
  }

  /** A change records the changed fields only, and treats a blank value as no value. */
  @Test
  void change_recordsOnlyChangedFields() {
    final AuditData.Change change = AuditData.Change.between(
        Map.of("name", "A", "email", "", "phone_number", "1"),
        Map.of("name", "B", "email", "  ", "phone_number", "1"),
        false);

    assertThat(change).isNotNull();
    assertThat(change.changedFields()).containsExactly("name");
    assertThat(change.oldValues()).isNull();
  }

  /** Nothing changed means no change at all. */
  @Test
  void change_nothingChanged_isNull() {
    assertThat(AuditData.Change.between(Map.of("name", "A"), Map.of("name", "A"), true)).isNull();
  }

  /** On a client both the previous and the new values of the changed fields are recorded. */
  @Test
  void clientUpdated_recordsOldAndNewValues() {
    final ManagedClientInfo before = client(List.of("https://a.example.com/cb"), Set.of("demo"));
    final ManagedClientInfo after = client(List.of("https://b.example.com/cb"), Set.of("demo"));

    this.publisher.publishEvent(IamAuditEvent.clientUpdated("c1", AuditData.Change.between(
        AuditData.Client.settingsOf(before), AuditData.Client.settingsOf(after), true)));

    final AuditEvent event = this.publisher.single("client_updated");
    assertThat(object(event, "client")).containsExactly(Map.entry("client_id", "c1"));
    assertThat(object(event, "change")).containsEntry("changed_fields", List.of("redirect_uris"));
    assertThat(object(event, "change")).containsEntry("old", Map.of("redirect_uris", List.of("https://a.example.com/cb")));
    assertThat(object(event, "change")).containsEntry("new", Map.of("redirect_uris", List.of("https://b.example.com/cb")));
  }

  /** A user deletion leaves the user out of each removed right, since {@code user} says who. */
  @Test
  void userDeleted_removedRightsWithoutUserMembers() {
    this.publisher.publishEvent(IamAuditEvent.userDeleted(
        AuditData.User.of("u1", "Klara"),
        List.of(new RightHolder("u1", "Klara", "5565552014", null, "admin"))));

    final AuditEvent event = this.publisher.single("user_deleted");
    assertThat(event.getData().get("removed_rights")).isEqualTo(List.of(
        Map.of("organization_identifier", "5565552014", "right", "admin")));
  }

  /** Other deletions name the user holding each removed right. */
  @Test
  void functionDetached_removedRightsNameTheUser() {
    this.publisher.publishEvent(IamAuditEvent.functionDetached("5565552014", "demo",
        List.of(new RightHolder("u1", null, "5565552014", "demo", "read"))));

    final AuditEvent event = this.publisher.single("function_detached");
    assertThat(event.getData()).containsEntry("organization_identifier", "5565552014");
    assertThat(event.getData()).containsEntry("function_id", "demo");
    assertThat(event.getData().get("removed_rights")).isEqualTo(List.of(Map.of(
        "user_id", "u1", "organization_identifier", "5565552014", "function_id", "demo", "right", "read")));
  }

  /** No removed rights means no {@code removed_rights} member. */
  @Test
  void organizationDeleted_noRights_noRemovedRightsMember() {
    this.publisher.publishEvent(IamAuditEvent.organizationDeleted("5565552014", "ICA", List.of()));

    assertThat(this.publisher.single("organization_deleted").getData())
        .containsOnlyKeys("organization_identifier", "organization_legal_name");
  }

  /** The principal comes from the security context, and is {@code system} without one. */
  @Test
  void principal_fromSecurityContextOrSystem() {
    this.publisher.publishEvent(IamAuditEvent.functionCreated("demo", null));
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("caller", null, List.of()));
    this.publisher.publishEvent(IamAuditEvent.functionCreated("demo", null));

    assertThat(this.publisher.events()).extracting(AuditEvent::getPrincipal).containsExactly("system", "caller");
  }

  /** A failed login with no known subject carries no principal at all, not {@code system}. */
  @Test
  void loginFailed_unknownSubject_noPrincipal() {
    this.publisher.publishEvent(IamErrorAuditEvent.loginFailed(null,
        new AuditData.Login(AuditData.Login.STANDARD, null, null, null, null),
        new OAuth2AuthenticationException(new OAuth2Error("invalid_token_response"), "bad response")));

    final AuditEvent event = this.publisher.single("login_failed");
    final JsonMapper mapper = JsonMapper.builder().build();
    assertThat(mapper.readTree(new JsonAuditEventMapper(mapper).write(event)).has("principal")).isFalse();
    assertThat(object(event, "error")).containsEntry("code", "invalid_token_response");
    assertThat(object(event, "login")).containsExactly(Map.entry("method", "standard"));
  }

  /** An operation failure carries the operation, the target and the library's error object. */
  @Test
  void operationFailed_carriesOperationTargetAndError() {
    this.publisher.publishEvent(IamErrorAuditEvent.operationFailed(
        new AuditData.Operation(AuditOperations.ATTACH_FUNCTION, null),
        AuditData.Target.none().organization("5565552014").function("demo"),
        new KeycloakAdminException("down")));

    final AuditEvent event = this.publisher.single("operation_failed");
    assertThat(object(event, "operation")).containsExactly(Map.entry("name", "attach_function"));
    assertThat(event.getData()).containsEntry("organization_identifier", "5565552014");
    assertThat(object(event, "error")).containsEntry("code", "keycloak_error").containsEntry("message", "down");
  }

  /** The correlation ID of the publishing thread is attached. */
  @Test
  void correlationId_fromHolder() {
    CorrelationIDHolder.set(CorrelationID.of("flow-1"));
    this.publisher.publishEvent(IamAuditEvent.importCancelled("b1"));

    final AuditEvent event = this.publisher.single("import_cancelled");
    assertThat(event.getCorrelationId())
        .isEqualTo(CorrelationID.of("flow-1"));
    assertThat(object(event, "import")).containsExactly(Map.entry("batch_id", "b1"));
  }

  /** An event serializes to a single line of JSON carrying the common fields and the data. */
  @Test
  void serializesToOneJsonLine() {
    CorrelationIDHolder.set(CorrelationID.of("flow-1"));
    this.publisher.publishEvent(IamAuditEvent.userRightAssigned(
        AuditData.User.of("u1", "Klara"), "5565552014", "demo", "admin", "read", AuditData.Import.job("b1", "j1")));

    final JsonMapper mapper = JsonMapper.builder().build();
    final String json = new JsonAuditEventMapper(mapper).write(this.publisher.single("user_right_assigned"));
    assertThat(json).doesNotContain("\n");

    final JsonNode node = mapper.readTree(json);
    assertThat(node.get("type").asString()).isEqualTo("user_right_assigned");
    assertThat(node.get("correlation_id").asString()).isEqualTo("flow-1");
    assertThat(node.get("principal").asString()).isEqualTo("system");
    assertThat(node.get("data").get("previous_right").asString()).isEqualTo("read");
    assertThat(node.get("data").get("import").get("job_id").asString()).isEqualTo("j1");
  }

  private static ManagedClientInfo client(final List<String> redirectUris, final Set<String> functions) {
    return new ManagedClientInfo("uuid", "c1", "Client", true, false, functions, false, redirectUris,
        "https://c.example.com/jwks", null, false, true, true, true);
  }

}
