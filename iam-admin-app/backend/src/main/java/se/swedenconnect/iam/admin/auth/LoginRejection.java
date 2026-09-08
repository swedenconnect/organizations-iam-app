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
package se.swedenconnect.iam.admin.auth;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

/**
 * The reason a login was rejected, expressed as a message code and the arguments the message needs.
 *
 * <p>No finished sentence is ever stored. The text is produced when the frontend asks for it, from
 * the message bundles, in every language the application supports.</p>
 *
 * @param code the message code, resolvable through the application's {@code MessageSource}
 * @param arguments the arguments to substitute into the message, in declaration order
 * @author Martin Lindström
 */
public record LoginRejection(@NonNull String code, @NonNull List<String> arguments) implements Serializable {

  /** The {@code org_rights} claim is absent or empty. */
  public static final String NO_ORGANIZATIONAL_RIGHTS = "login.error.noOrganizationalRights";

  /** No admin right anywhere, and the login was not constrained. */
  public static final String NO_ADMIN_RIGHT = "login.error.noAdminRight";

  /** No admin right for the constrained organization. Argument: the organization identifier. */
  public static final String NO_ADMIN_RIGHT_IN_ORGANIZATION = "login.error.noAdminRightInOrganization";

  /** No admin right for the constrained function. Argument: the function identifier. */
  public static final String NO_ADMIN_RIGHT_FOR_FUNCTION = "login.error.noAdminRightForFunction";

  /**
   * No admin right for the constrained function within the constrained organization. Arguments: the
   * function identifier and the organization identifier.
   */
  public static final String NO_ADMIN_RIGHT_FOR_FUNCTION_IN_ORGANIZATION =
      "login.error.noAdminRightForFunctionInOrganization";

  /**
   * Constructs a {@code LoginRejection}, defensively copying the arguments.
   *
   * @param code the message code; must not be null
   * @param arguments the message arguments; must not be null
   */
  public LoginRejection {
    arguments = List.copyOf(arguments);
  }

  /**
   * The rejection used when the {@code org_rights} claim is absent or empty.
   *
   * @return a rejection; never null
   */
  public static @NonNull LoginRejection noOrganizationalRights() {
    return new LoginRejection(NO_ORGANIZATIONAL_RIGHTS, List.of());
  }

  /**
   * The rejection used when the user holds no admin right within the effective claim.
   *
   * <p>Which of the four codes applies is decided by the constraints the login was made under. The
   * library's finer distinction between "nothing at all in this organization" and "no admin right in
   * this organization" is deliberately collapsed: it is not useful to the person reading the
   * message.</p>
   *
   * @param orgConstraint the organization the login was constrained to, or {@code null}
   * @param funcConstraint the function the login was constrained to, or {@code null}
   * @return a rejection; never null
   */
  public static @NonNull LoginRejection insufficientAdminRights(
      final @Nullable String orgConstraint, final @Nullable String funcConstraint) {

    if (orgConstraint != null && funcConstraint != null) {
      return new LoginRejection(
          NO_ADMIN_RIGHT_FOR_FUNCTION_IN_ORGANIZATION, List.of(funcConstraint, orgConstraint));
    }
    if (orgConstraint != null) {
      return new LoginRejection(NO_ADMIN_RIGHT_IN_ORGANIZATION, List.of(orgConstraint));
    }
    if (funcConstraint != null) {
      return new LoginRejection(NO_ADMIN_RIGHT_FOR_FUNCTION, List.of(funcConstraint));
    }
    return new LoginRejection(NO_ADMIN_RIGHT, List.of());
  }

}
