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
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;

import java.util.List;

/**
 * Parsed representation of the {@code org_rights} OIDC claim.
 *
 * <p>A claim is either a superuser marker or a list of organization entries, each holding
 * a set of function-level rights.</p>
 *
 * @author Martin Lindström
 */
public record OrgRightsClaim(boolean superuser, @NonNull List<OrgEntry> orgEntries) {

  /**
   * Represents a single organization with its associated function rights.
   *
   * @param orgIdentifier the organization identifier
   * @param name the localized name of the organization
   * @param functions the list of function-right entries for this organization
   */
  public record OrgEntry(
      @NonNull OrganizationID orgIdentifier,
      @NonNull LocalizedString name,
      @NonNull List<FunctionEntry> functions) {
  }

  /**
   * Represents a function-right pair within an organization.
   *
   * <p>The {@code function} field is either a named function identifier (e.g. {@code walletreg})
   * or the literal {@code *} indicating an org-wide right that applies to all functions.</p>
   *
   * @param function the function identifier, or {@code *} for org-wide
   * @param right the right level: {@code admin}, {@code write}, or {@code read}
   */
  public record FunctionEntry(@NonNull String function, @NonNull String right) {
  }

}
