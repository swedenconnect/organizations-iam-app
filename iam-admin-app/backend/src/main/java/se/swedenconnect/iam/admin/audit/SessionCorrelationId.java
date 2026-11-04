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

import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;

import java.util.regex.Pattern;

/**
 * Keeps the correlation ID of a session. The ID is assigned when a login starts, kept for the rest of
 * the session, and installed in {@link CorrelationIDHolder} for every request in the session by
 * {@link CorrelationIdFilter}.
 *
 * @author Martin Lindström
 */
public final class SessionCorrelationId {

  /** Session attribute holding the correlation ID. */
  public static final String SESSION_ATTR = "audit.correlationId";

  /** The query parameter of the SSO endpoint through which a caller may supply a correlation ID. */
  public static final String PARAMETER = "correlation-id";

  /** The accepted format of a supplied correlation ID. */
  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

  /**
   * Tells whether a supplied correlation ID has an accepted format: letters, digits, {@code -},
   * {@code _}, {@code .} and {@code :} only, and at most 128 characters.
   *
   * @param value the value, or {@code null}
   * @return {@code true} if the value may be used
   */
  public static boolean isValid(final @Nullable String value) {
    return value != null && VALID.matcher(value).matches();
  }

  /**
   * Returns the correlation ID of the session, or {@code null} if it has none.
   *
   * @param session the session, or {@code null}
   * @return the correlation ID, or {@code null}
   */
  public static @Nullable CorrelationID get(final @Nullable HttpSession session) {
    if (session == null) {
      return null;
    }
    return session.getAttribute(SESSION_ATTR) instanceof final String value ? CorrelationID.of(value) : null;
  }

  /**
   * Assigns a correlation ID to the session when a login starts, unless the session already has one,
   * and installs it for the current request.
   *
   * <p>A supplied value is used only if it has an accepted format. An invalid value is ignored and a
   * new ID generated, so that a bad value from another application never locks the user out.</p>
   *
   * @param session the session
   * @param supplied the value supplied by the caller, or {@code null}
   * @return the correlation ID of the session
   */
  public static @NonNull CorrelationID assign(final @NonNull HttpSession session, final @Nullable String supplied) {
    CorrelationID id = get(session);
    if (id == null) {
      id = isValid(supplied) ? CorrelationID.of(supplied) : CorrelationID.generate();
      session.setAttribute(SESSION_ATTR, id.getValue());
    }
    CorrelationIDHolder.set(id);
    return id;
  }

  private SessionCorrelationId() {
  }

}
