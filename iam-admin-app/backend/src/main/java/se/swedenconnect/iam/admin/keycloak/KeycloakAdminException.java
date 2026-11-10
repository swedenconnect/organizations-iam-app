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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thrown when a KeyCloak Admin REST API operation fails unrecoverably — e.g. when the service
 * account token cannot be obtained or when the server returns a non-2xx response.
 *
 * <p>An exception raised from an HTTP response carries the status code and the {@code errorMessage}
 * Keycloak puts in the response body, see {@link #forResponse}. Callers use those to tell a
 * conflict the administrator can act on, such as an email address that is already registered, from
 * a genuine server failure. The Keycloak message is meant for the log and for matching in code —
 * it is never passed on to a client.</p>
 *
 * @author Martin Lindström
 */
public class KeycloakAdminException extends RuntimeException {

  /** Parses the Keycloak error body; the body is small and the mapper is used read-only. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The HTTP status returned by Keycloak, or {@code 0} when the failure had no response. */
  private final int status;

  /** The {@code errorMessage} from the Keycloak response body, when there was one. */
  private final @Nullable String keycloakErrorMessage;

  public KeycloakAdminException(final @NonNull String message) {
    super(message);
    this.status = 0;
    this.keycloakErrorMessage = null;
  }

  public KeycloakAdminException(final @NonNull String message, final @NonNull Throwable cause) {
    super(message, cause);
    this.status = 0;
    this.keycloakErrorMessage = null;
  }

  public KeycloakAdminException(
      final @NonNull String message,
      final int status,
      final @Nullable String keycloakErrorMessage,
      final @NonNull Throwable cause) {
    super(message, cause);
    this.status = status;
    this.keycloakErrorMessage = keycloakErrorMessage;
  }

  /**
   * Wraps an error response from the Keycloak admin API, keeping the status code and the
   * {@code errorMessage} from the response body.
   *
   * @param operation the request that failed, e.g. {@code "POST /users"}, for the message
   * @param e the response exception
   * @return the exception to throw
   */
  public static @NonNull KeycloakAdminException forResponse(
      final @NonNull String operation, final @NonNull RestClientResponseException e) {
    return new KeycloakAdminException(
        "Keycloak admin API returned " + e.getStatusCode() + " for " + operation,
        e.getStatusCode().value(),
        errorMessageOf(e.getResponseBodyAsString()),
        e);
  }

  /**
   * Returns the HTTP status Keycloak returned, or {@code 0} when the call failed without a
   * response at all.
   *
   * @return the status code
   */
  public int getStatus() {
    return this.status;
  }

  /**
   * Returns the {@code errorMessage} Keycloak put in the response body, or {@code null} when the
   * body carried none.
   *
   * @return the Keycloak error message, or {@code null}
   */
  public @Nullable String getKeycloakErrorMessage() {
    return this.keycloakErrorMessage;
  }

  /**
   * Tells whether Keycloak refused the operation as a conflict (409), i.e. because the realm
   * already holds what the request tried to create or change.
   *
   * @return {@code true} for a 409 response
   */
  public boolean isConflict() {
    return this.status == 409;
  }

  /**
   * Reads the {@code errorMessage} field out of a Keycloak error body. A body that is empty, not
   * JSON, or carries no such field yields {@code null}.
   *
   * @param body the response body
   * @return the error message, or {@code null}
   */
  private static @Nullable String errorMessageOf(final @Nullable String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      final JsonNode node = MAPPER.readTree(body).get("errorMessage");
      return node != null && node.isString() ? node.asString() : null;
    }
    catch (final RuntimeException e) {
      return null;
    }
  }

}
