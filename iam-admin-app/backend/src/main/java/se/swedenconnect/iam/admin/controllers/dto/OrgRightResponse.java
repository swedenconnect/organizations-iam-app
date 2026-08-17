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
package se.swedenconnect.iam.admin.controllers.dto;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * JSON representation of the per-org rights held by the current user.
 *
 * <p>{@code orgLevelRight} mirrors the {@code org_level_right} field of the {@code org_rights}
 * claim. It is provenance only — it says the right was granted at the organization level, and is
 * used solely to decide whether the user may administer the organization itself. Effective rights
 * come from {@code functions}, which lists only functions attached to the organization and may be
 * empty.</p>
 *
 * @author Martin Lindström
 */
public record OrgRightResponse(
    @NonNull String orgIdentifier,
    @Nullable String orgLevelRight,
    @NonNull List<FunctionRightResponse> functions) {
}
