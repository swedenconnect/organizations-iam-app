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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.spring.audit.AuditEvent;
import se.swedenconnect.spring.audit.AuditEventBuilder;
import se.swedenconnect.spring.audit.AuditEventContext;
import se.swedenconnect.spring.audit.AuditType;
import se.swedenconnect.spring.audit.appevents.AbstractErrorEvent;
import se.swedenconnect.spring.audit.value.AuditValue;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * An application event reporting a failure: {@code login_failed} or {@code operation_failed}. It
 * builds on the library's error event, so the audit event carries the library's {@code error} object
 * in addition to the data added here.
 *
 * @author Martin Lindström
 */
public final class IamErrorAuditEvent extends AbstractErrorEvent {

  @Serial
  private static final long serialVersionUID = 1L;

  /** Error code for a failure reported by Keycloak. */
  public static final String KEYCLOAK_ERROR = "keycloak_error";

  /** Error code for any other failure. */
  public static final String INTERNAL_ERROR = "internal_error";

  /** Error code for a failed login that carries no OAuth2 error code of its own. */
  public static final String AUTHENTICATION_FAILED = "authentication_failed";

  /** The audit type. */
  private final AuditType type;

  /** The data fields added to the error. */
  private final List<AuditValue<? extends Serializable>> data;

  /** Whether the principal is assigned by the event rather than taken from the context. */
  private final boolean principalAssigned;

  /** The assigned principal. */
  private final @Nullable String principal;

  private IamErrorAuditEvent(final @NonNull AuditType type, final @NonNull String code,
      final @NonNull Exception exception, final @NonNull List<AuditValue<? extends Serializable>> data,
      final boolean principalAssigned, final @Nullable String principal) {
    super(code, exception.getMessage(), exception.getClass(), null);
    this.type = type;
    this.data = List.copyOf(data);
    this.principalAssigned = principalAssigned;
    this.principal = principal;
  }

  /**
   * {@code login_failed}
   *
   * @param user the user if known, otherwise {@code null}
   * @param login the login
   * @param exception what made the login fail
   * @return the event
   */
  public static @NonNull IamErrorAuditEvent loginFailed(final AuditData.@Nullable User user,
      final AuditData.@NonNull Login login, final @NonNull Exception exception) {
    final String code = exception instanceof final OAuth2AuthenticationException oauth2
        ? oauth2.getError().getErrorCode()
        : AUTHENTICATION_FAILED;
    final List<AuditValue<? extends Serializable>> data = new ArrayList<>();
    if (user != null) {
      data.add(user.toAuditValue());
    }
    data.add(login.toAuditValue());
    return new IamErrorAuditEvent(IamAuditTypes.LOGIN_FAILED, code, exception, data, true,
        user != null ? user.userId() : null);
  }

  /**
   * {@code operation_failed}
   *
   * @param operation the operation
   * @param target what the operation was aimed at
   * @param exception what stopped the operation
   * @return the event
   */
  public static @NonNull IamErrorAuditEvent operationFailed(final AuditData.@NonNull Operation operation,
      final AuditData.@NonNull Target target, final @NonNull Exception exception) {
    final List<AuditValue<? extends Serializable>> data = new ArrayList<>();
    data.add(operation.toAuditValue());
    data.addAll(target.toAuditValues());
    final String code = exception instanceof KeycloakAdminException ? KEYCLOAK_ERROR : INTERNAL_ERROR;
    return new IamErrorAuditEvent(IamAuditTypes.OPERATION_FAILED, code, exception, data, false, null);
  }

  /**
   * Returns a copy of this event with the given principal, replacing the one in the security context.
   *
   * @param principal the principal, or {@code null} for none at all
   * @return a new event
   */
  public @NonNull IamErrorAuditEvent withPrincipal(final @Nullable String principal) {
    return new IamErrorAuditEvent(this, principal);
  }

  private IamErrorAuditEvent(final @NonNull IamErrorAuditEvent other, final @Nullable String principal) {
    super(other.getError().code(), other.getError().message(), other.getError().exception(),
        other.getError().details());
    this.type = other.type;
    this.data = other.data;
    this.principalAssigned = true;
    this.principal = principal;
  }

  /** {@inheritDoc} */
  @Override
  protected @NonNull AuditEvent transform(
      final @NonNull AuditEventBuilder eventBuilder, final @NonNull AuditEventContext context) {
    this.data.forEach(eventBuilder::dataField);
    if (this.principalAssigned) {
      eventBuilder.principal(this.principal);
    }
    return eventBuilder.build();
  }

  /** {@inheritDoc} */
  @Override
  protected @NonNull AuditType getAuditType() {
    return this.type;
  }

}
