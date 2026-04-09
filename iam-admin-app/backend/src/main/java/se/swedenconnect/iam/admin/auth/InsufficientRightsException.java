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
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.io.Serial;

/**
 * Thrown when a parsed {@link OrgRightsClaim} does not satisfy the required admin right, optionally constrained to a
 * specific organization and/or function.
 *
 * <p>
 * The exception message contains a human-readable description of why the constraint was not satisfied, suitable for
 * logging and for surfacing to the user.
 * </p>
 *
 * @author Martin Lindström
 */
public class InsufficientRightsException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Constructs an {@code InsufficientRightsException} with the given reason.
   *
   * @param reason a human-readable description of why the rights check failed; must not be null
   */
  public InsufficientRightsException(final @NonNull String reason) {
    super(reason);
  }

}
