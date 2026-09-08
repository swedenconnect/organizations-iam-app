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
import org.jspecify.annotations.Nullable;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;

import java.util.List;

/**
 * Parsed representation of the {@code org_rights} OIDC claim.
 *
 * <p>A claim is either a superuser marker or a list of organization entries, each holding
 * a set of function-level rights.</p>
 *
 * <p>Use {@link #organizations()} to enumerate the organizations the user is associated with, and
 * the authorities built by {@link OrgRightsClaimParser} to decide what the user may do.</p>
 *
 * @author Martin Lindström
 */
public record OrgRightsClaim(boolean superuser, @NonNull List<OrgEntry> orgEntries) {

  /**
   * Enumerates the organizations the user is associated with.
   *
   * <p>This answers "<em>which</em> organizations is this user associated with", which is a
   * different question from "<em>what</em> may this user do for function X in organization Y".
   * The latter is answered by the granted authorities, which are derived per function entry and
   * therefore omit an organization that has no functions attached — even when the user genuinely
   * administers it at the organization level. Use this method whenever the goal is to list or
   * display organizations, and the authorities whenever the goal is an access decision.</p>
   *
   * <p>For a <strong>superuser</strong> the claim carries no organization entries, so this returns
   * an empty list. A caller that needs the full organization list for a superuser must fetch it
   * from the IAM Service API ({@code /iam-api/v1/organizations}) instead.</p>
   *
   * @return one {@link Organization} per organization entry, in claim order; never {@code null}
   */
  public @NonNull List<Organization> organizations() {
    return this.orgEntries.stream()
        .map(e -> new Organization(e.orgIdentifier(), e.legalName(), e.name(), e.orgLevelRight()))
        .toList();
  }

  /**
   * An organization the user is associated with, without any function-level right information.
   *
   * <p>{@code orgLevelRight} carries the same meaning as in {@link OrgEntry}: it is provenance,
   * recording that the right was granted at the organization level, and confers no access to any
   * function. It is {@code null} when the user's rights in this organization are all
   * function-level.</p>
   *
   * <p>See {@link OrgEntry} for how {@code legalName} and {@code name} relate.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param legalName the organization's registered legal name, from the
   *     {@code organization_legal_name} claim member; always present
   * @param name the localized display names of the organization
   * @param orgLevelRight the right granted at the organization level ({@code admin}, {@code write}
   *     or {@code read}), or {@code null} if the user holds no org-level right
   */
  public record Organization(
      @NonNull OrganizationID orgIdentifier,
      @NonNull String legalName,
      @NonNull LocalizedString name,
      @Nullable String orgLevelRight) {
  }

  /**
   * Represents a single organization with its associated function rights.
   *
   * <p>{@code orgLevelRight} is <em>provenance only</em>: it records that the user was granted a
   * right at the organization level, but confers no access in itself. A right granted at the
   * organization level has already been expanded by the protocol mapper into one
   * {@link FunctionEntry} per function attached to the organization, so effective rights come
   * exclusively from {@link #functions()}. Treating {@code orgLevelRight} as granting access to a
   * function would grant access to functions that are not attached to the organization. Its one
   * legitimate use is deciding whether the user may administer the organization itself.</p>
   *
   * <p>{@link #functions()} may be empty while {@code orgLevelRight} is set — that is an
   * organization with no attached functions, which consumers must handle without error.</p>
   *
   * <h2>Names</h2>
   *
   * <p>{@code legalName} is the organization's name as registered at Bolagsverket, taken from the
   * {@code organization_legal_name} claim member. It is always present and is <strong>the correct
   * way to read the registered name</strong>.</p>
   *
   * <p>{@code name} holds the optional Swedish and English <em>display</em> names, collected from
   * the {@code organization_name#*} claim members. Read it when a name is to be shown for a given
   * language; it falls back to the legal name on its own, because the claim also carries the legal
   * name under the untagged {@code organization_name} member, which lands in {@code name} under the
   * no-language key. That untagged entry is a duplicate kept for backwards compatibility and must
   * not be relied on: use {@code legalName} when the registered name is what is wanted.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param legalName the organization's registered legal name, from the
   *     {@code organization_legal_name} claim member; always present
   * @param name the localized display names of the organization, with the legal name as the
   *     no-language fallback
   * @param orgLevelRight the right granted at the organization level ({@code admin}, {@code write}
   *     or {@code read}), or {@code null} if the user holds no org-level right
   * @param functions the list of function-right entries for this organization
   */
  public record OrgEntry(
      @NonNull OrganizationID orgIdentifier,
      @NonNull String legalName,
      @NonNull LocalizedString name,
      @Nullable String orgLevelRight,
      @NonNull List<FunctionEntry> functions) {
  }

  /**
   * Represents a function-right pair within an organization.
   *
   * <p>The {@code function} field is always a named function identifier (e.g. {@code walletreg})
   * of a function attached to the organization.</p>
   *
   * @param function the function identifier
   * @param right the right level: {@code admin}, {@code write}, or {@code read}
   */
  public record FunctionEntry(@NonNull String function, @NonNull String right) {
  }

}
