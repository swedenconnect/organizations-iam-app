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
package se.swedenconnect.iam.admin.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that a request Keycloak refused is answered with the kind of error Keycloak gave, and that
 * the response says nothing about the deployment. See
 * <a href="https://github.com/swedenconnect/organizations-iam-app/issues/136">issue #136</a>.
 *
 * @author Per Fredrik Plars
 */
class KeycloakErrorAdviceTest {

  private final KeycloakErrorAdvice advice = new KeycloakErrorAdvice();

  /** The 400 Keycloak answers for a name its user profile refuses reaches the caller as a 400. */
  @Test
  void badRequestFromKeycloak_isABadRequest() {
    final ResponseEntity<Map<String, String>> response = this.advice.handle(response(
        HttpStatus.BAD_REQUEST, "{\"errorMessage\":\"error-person-name-invalid-character\"}"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", KeycloakErrorAdvice.REJECTED));
  }

  /** Every other 4xx is passed on as it stands. */
  @Test
  void otherClientErrors_keepTheirStatus() {
    assertThat(this.advice.handle(response(HttpStatus.CONFLICT, "")).getStatusCode().value())
        .isEqualTo(409);
    assertThat(this.advice.handle(response(HttpStatus.NOT_FOUND, "")).getStatusCode().value())
        .isEqualTo(404);
  }

  /** A Keycloak that failed is a server error, not the caller's fault. */
  @Test
  void serverErrorFromKeycloak_isAServerError() {
    final ResponseEntity<Map<String, String>> response =
        this.advice.handle(response(HttpStatus.INTERNAL_SERVER_ERROR, ""));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", KeycloakErrorAdvice.FAILED));
  }

  /** A failure without a response at all, such as a connection refused, is a server error. */
  @Test
  void failureWithoutAResponse_isAServerError() {
    final ResponseEntity<Map<String, String>> response =
        this.advice.handle(new KeycloakAdminException("Keycloak admin API call failed for POST /users"));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", KeycloakErrorAdvice.FAILED));
  }

  /** Neither what Keycloak said nor where the call failed belongs in the response. */
  @Test
  void theResponseSaysNothingAboutTheDeployment() {
    final ResponseEntity<Map<String, String>> response = this.advice.handle(
        KeycloakAdminException.forResponse("POST http://keycloak.internal:8080/admin/realms/orgiam/users",
            new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request",
                "{\"errorMessage\":\"error-person-name-invalid-character\",\"field\":\"firstName\"}"
                    .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));

    assertThat(response.getBody()).containsOnlyKeys("reason");
    assertThat(response.getBody().toString())
        .doesNotContain("keycloak.internal", "orgiam", "error-person-name-invalid-character");
  }

  private static KeycloakAdminException response(final HttpStatus status, final String body) {
    return KeycloakAdminException.forResponse("POST /users", new HttpClientErrorException(
        status, status.getReasonPhrase(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
  }

}
