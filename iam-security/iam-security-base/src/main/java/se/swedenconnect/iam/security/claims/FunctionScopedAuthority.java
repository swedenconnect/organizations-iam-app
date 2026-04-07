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
package se.swedenconnect.iam.security.claims;

import org.jspecify.annotations.NonNull;
import org.springframework.security.core.GrantedAuthority;
import se.swedenconnect.iam.commons.types.OrganizationID;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * A {@link GrantedAuthority} for applications operating in function-scoped mode.
 *
 * <p>In function-scoped mode the application is configured with a single function identifier
 * (e.g. {@code walletreg}) via the {@code iam.security.function} property. Because the
 * function is a constant for the application, it is not encoded in the authority string —
 * only the organization identifier and the right are needed.</p>
 *
 * <p>Authority string form: {@code {orgIdentifier}:{right}}, for example
 * {@code 5590026042:write} or {@code 5561234567:admin}.</p>
 *
 * <p>The effective right is the highest right the user holds for the configured function
 * in the given organization — either via a direct function-level right or an org-wide
 * ({@code *}) right.</p>
 *
 * <p>Instances are created via the static factory methods {@link #of(OrganizationID, OrganizationRight)}
 * and {@link #parse(String)}.</p>
 *
 * @author Martin Lindström
 * @see OrganizationalAuthority
 */
public final class FunctionScopedAuthority implements GrantedAuthority, Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  /** The organization identifier. */
  private final OrganizationID orgIdentifier;

  /** The effective right level for the configured function in this organization. */
  private final OrganizationRight right;

  /**
   * Private constructor — use {@link #of(OrganizationID, OrganizationRight)} or {@link #parse(String)}.
   *
   * @param orgIdentifier the organization identifier; must not be null
   * @param right the right level; must not be null
   */
  private FunctionScopedAuthority(
      final @NonNull OrganizationID orgIdentifier,
      final @NonNull OrganizationRight right) {
    this.orgIdentifier = Objects.requireNonNull(orgIdentifier, "orgIdentifier must not be null");
    this.right = Objects.requireNonNull(right, "right must not be null");
  }

  /**
   * Creates a {@code FunctionScopedAuthority} from its components.
   *
   * @param orgIdentifier the organization identifier; must not be null
   * @param right the effective right level; must not be null
   * @return a new {@code FunctionScopedAuthority}; never null
   */
  public static @NonNull FunctionScopedAuthority of(
      final @NonNull OrganizationID orgIdentifier,
      final @NonNull OrganizationRight right) {
    return new FunctionScopedAuthority(orgIdentifier, right);
  }

  /**
   * Parses an authority string of the form {@code {orgIdentifier}:{right}} into a
   * {@code FunctionScopedAuthority}.
   *
   * <p>Example: {@code FunctionScopedAuthority.parse("5590026042:write")}</p>
   *
   * @param authority the authority string to parse; must not be null and must contain exactly one colon
   * @return the parsed {@code FunctionScopedAuthority}; never null
   * @throws IllegalArgumentException if the string does not have the expected format, contains an unknown right,
   *     or the organization identifier is not a valid Swedish organization number
   */
  public static @NonNull FunctionScopedAuthority parse(final @NonNull String authority) {
    Objects.requireNonNull(authority, "authority must not be null");
    final int colon = authority.indexOf(':');
    if (colon < 0 || colon != authority.lastIndexOf(':')) {
      throw new IllegalArgumentException(
          "Authority string does not match {orgIdentifier}:{right}: '" + authority + "'");
    }
    final String orgId = authority.substring(0, colon);
    final String rightStr = authority.substring(colon + 1);
    if (orgId.isEmpty() || rightStr.isEmpty()) {
      throw new IllegalArgumentException(
          "Authority string contains empty segments: '" + authority + "'");
    }
    return new FunctionScopedAuthority(OrganizationID.of(orgId), OrganizationRight.parse(rightStr));
  }

  /**
   * Returns the authority string in the form {@code {orgIdentifier}:{right}}.
   *
   * @return the authority string; never null
   */
  @Override
  public @NonNull String getAuthority() {
    return this.orgIdentifier.toString() + ":" + this.right.getRight();
  }

  /**
   * Returns the organization identifier.
   *
   * @return the organization identifier; never null
   */
  public @NonNull OrganizationID getOrgIdentifier() {
    return this.orgIdentifier;
  }

  /**
   * Returns the effective right level for the configured function in this organization.
   *
   * @return the right level; never null
   */
  public @NonNull OrganizationRight getRight() {
    return this.right;
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || this.getClass() != o.getClass()) {
      return false;
    }
    final FunctionScopedAuthority that = (FunctionScopedAuthority) o;
    return Objects.equals(this.orgIdentifier, that.orgIdentifier) && this.right == that.right;
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return Objects.hash(this.orgIdentifier, this.right);
  }

  /** {@inheritDoc} */
  @Override
  public @NonNull String toString() {
    return this.getAuthority();
  }

}
