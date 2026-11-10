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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that an error response from the Keycloak admin API is wrapped with the status code and the
 * {@code errorMessage} from the response body, so that a caller can tell a conflict the
 * administrator can act on from a server failure.
 *
 * @author Per Fredrik Plars
 */
class KeycloakAdminExceptionTest {

  /** The status and the Keycloak message are both kept, and a 409 is reported as a conflict. */
  @Test
  void forResponse_conflict_keepsStatusAndMessage() {
    final KeycloakAdminException e = KeycloakAdminException.forResponse(
        "POST /users", response(HttpStatus.CONFLICT, "{\"errorMessage\":\"User exists with same email\"}"));

    assertThat(e.getStatus()).isEqualTo(409);
    assertThat(e.isConflict()).isTrue();
    assertThat(e.getKeycloakErrorMessage()).isEqualTo("User exists with same email");
    assertThat(e.getMessage()).isEqualTo("Keycloak admin API returned 409 CONFLICT for POST /users");
  }

  /** A response that is not a conflict is not reported as one. */
  @Test
  void forResponse_serverError_isNoConflict() {
    final KeycloakAdminException e = KeycloakAdminException.forResponse(
        "PUT /users/abc", response(HttpStatus.INTERNAL_SERVER_ERROR, ""));

    assertThat(e.getStatus()).isEqualTo(500);
    assertThat(e.isConflict()).isFalse();
    assertThat(e.getKeycloakErrorMessage()).isNull();
  }

  /** A body that is not JSON, or carries no message, leaves the message unset rather than failing. */
  @Test
  void forResponse_bodyWithoutMessage_leavesTheMessageUnset() {
    assertThat(KeycloakAdminException.forResponse(
        "POST /users", response(HttpStatus.CONFLICT, "not json at all")).getKeycloakErrorMessage())
        .isNull();
    assertThat(KeycloakAdminException.forResponse(
        "POST /users", response(HttpStatus.CONFLICT, "{\"error\":\"unknown_error\"}")).getKeycloakErrorMessage())
        .isNull();
  }

  /** An exception raised without a response at all carries no status. */
  @Test
  void withoutResponse_carriesNoStatus() {
    final KeycloakAdminException e = new KeycloakAdminException("User not found: abc");

    assertThat(e.getStatus()).isZero();
    assertThat(e.isConflict()).isFalse();
    assertThat(e.getKeycloakErrorMessage()).isNull();
  }

  private static HttpClientErrorException response(final HttpStatus status, final String body) {
    return new HttpClientErrorException(
        status, status.getReasonPhrase(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
  }

}
