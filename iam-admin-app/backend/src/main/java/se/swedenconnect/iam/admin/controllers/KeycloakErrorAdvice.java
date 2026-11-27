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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;

import java.util.Map;

/**
 * Answers a request that Keycloak refused with the kind of error Keycloak gave, rather than
 * letting it fall through as a server error.
 *
 * <p>A {@link KeycloakAdminException} carrying a 4xx status means the request itself was wrong —
 * a name the realm's user profile refuses, for instance — so the caller is told so with that same
 * status. Anything else is a server error. See
 * <a href="https://github.com/swedenconnect/organizations-iam-app/issues/136">issue #136</a>.</p>
 *
 * <p>The response body is a reason code and nothing else. What Keycloak said, and where the call
 * failed, belong in the log: a stack trace or an internal URL in a response tells a caller about
 * the deployment and nothing it can act on.</p>
 *
 * <p>A controller that handles a Keycloak error itself, such as the conflict over an email address
 * that is already registered, answers before this advice is reached.</p>
 *
 * @author Per Fredrik Plars
 */
@RestControllerAdvice
@Slf4j
public class KeycloakErrorAdvice {

  /** The caller's request was refused by Keycloak. */
  static final String REJECTED = "KEYCLOAK_REJECTED";

  /** Keycloak could not carry the operation out. */
  static final String FAILED = "KEYCLOAK_ERROR";

  /**
   * Maps a Keycloak admin API failure to a response.
   *
   * @param e the exception
   * @return the response: the status Keycloak answered with for a 4xx, otherwise 500
   */
  @ExceptionHandler(KeycloakAdminException.class)
  public ResponseEntity<Map<String, String>> handle(final @NonNull KeycloakAdminException e) {
    final HttpStatus status = HttpStatus.resolve(e.getStatus());
    if (status != null && status.is4xxClientError()) {
      log.info("Keycloak refused the request with {}: {} ({})",
          status.value(), e.getKeycloakErrorMessage(), e.getMessage());
      return ResponseEntity.status(status).body(Map.of("reason", REJECTED));
    }
    log.error("Keycloak admin API call failed: {}", e.getMessage(), e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("reason", FAILED));
  }

}
