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
 * A {@link GrantedAuthority} representing an organizational right for a specific function.
 *
 * <p>The authority string follows the scope naming convention used throughout the system:
 * {@code {orgIdentifier}:{functionId}:{right}}, for example {@code 5590026042:walletreg:write}.</p>
 *
 * <p>The {@code *} function identifier denotes an org-wide right covering all functions,
 * e.g. {@code 5590026042:*:admin}.</p>
 *
 * <p>Instances are created via the static factory methods {@link #of(OrganizationID, String, OrganizationRight)}
 * and {@link #parse(String)}.</p>
 *
 * @author Martin Lindström
 */
public final class OrganizationalAuthority implements GrantedAuthority, Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  /** The organization identifier. */
  private final OrganizationID orgIdentifier;

  /** The function identifier, or {@code *} for an org-wide right. */
  private final String functionId;

  /** The right level. */
  private final OrganizationRight right;

  /**
   * Private constructor — use {@link #of(OrganizationID, String, OrganizationRight)} or {@link #parse(String)}.
   *
   * @param orgIdentifier the organization identifier; must not be null
   * @param functionId the function identifier; must not be null
   * @param right the right level; must not be null
   */
  private OrganizationalAuthority(
      final @NonNull OrganizationID orgIdentifier,
      final @NonNull String functionId,
      final @NonNull OrganizationRight right) {
    this.orgIdentifier = Objects.requireNonNull(orgIdentifier, "orgIdentifier must not be null");
    this.functionId = Objects.requireNonNull(functionId, "functionId must not be null");
    this.right = Objects.requireNonNull(right, "right must not be null");
  }

  /**
   * Creates an {@code OrganizationalAuthority} from its components.
   *
   * @param orgIdentifier the organization identifier; must not be null
   * @param functionId the function identifier, or {@code *} for org-wide; must not be null
   * @param right the right level; must not be null
   * @return a new {@code OrganizationalAuthority}; never null
   */
  public static @NonNull OrganizationalAuthority of(
      final @NonNull OrganizationID orgIdentifier,
      final @NonNull String functionId,
      final @NonNull OrganizationRight right) {
    return new OrganizationalAuthority(orgIdentifier, functionId, right);
  }

  /**
   * Parses an authority string of the form {@code {orgIdentifier}:{functionId}:{right}} into an
   * {@code OrganizationalAuthority}.
   *
   * <p>Example: {@code OrganizationalAuthority.parse("5590026042:walletreg:write")}</p>
   *
   * @param authority the authority string to parse; must not be null and must contain exactly two colons
   * @return the parsed {@code OrganizationalAuthority}; never null
   * @throws IllegalArgumentException if the string does not have the expected format, contains an unknown right,
   *     or the organization identifier is not a valid Swedish organization number
   */
  public static @NonNull OrganizationalAuthority parse(final @NonNull String authority) {
    Objects.requireNonNull(authority, "authority must not be null");
    final int first = authority.indexOf(':');
    final int last = authority.lastIndexOf(':');
    if (first < 0 || first == last) {
      throw new IllegalArgumentException(
          "Authority string does not match {orgIdentifier}:{functionId}:{right}: '" + authority + "'");
    }
    final String orgId = authority.substring(0, first);
    final String funcId = authority.substring(first + 1, last);
    final String rightStr = authority.substring(last + 1);
    if (orgId.isEmpty() || funcId.isEmpty() || rightStr.isEmpty()) {
      throw new IllegalArgumentException(
          "Authority string contains empty segments: '" + authority + "'");
    }
    return new OrganizationalAuthority(OrganizationID.of(orgId), funcId, OrganizationRight.parse(rightStr));
  }

  /**
   * Returns the authority string in the form {@code {orgIdentifier}:{functionId}:{right}}.
   *
   * @return the authority string; never null
   */
  @Override
  public @NonNull String getAuthority() {
    return this.orgIdentifier.toString() + ":" + this.functionId + ":" + this.right.getRight();
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
   * Returns the function identifier, or {@code *} for an org-wide right.
   *
   * @return the function identifier; never null
   */
  public @NonNull String getFunctionId() {
    return this.functionId;
  }

  /**
   * Returns the right level.
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
    final OrganizationalAuthority that = (OrganizationalAuthority) o;
    return Objects.equals(this.orgIdentifier, that.orgIdentifier)
        && Objects.equals(this.functionId, that.functionId)
        && this.right == that.right;
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return Objects.hash(this.orgIdentifier, this.functionId, this.right);
  }

  /** {@inheritDoc} */
  @Override
  public @NonNull String toString() {
    return this.getAuthority();
  }

}
