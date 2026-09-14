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
package se.swedenconnect.iam.admin.controllers.dto.impexp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A single right entry attached to a {@link BundleUserEntry}. When {@code function_id} is
 * {@code null}, the right applies to the organization as a whole.
 *
 * @author PF Plars
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BundleUserRightEntry(
    @JsonProperty("org_identifier") @NonNull String orgIdentifier,
    @JsonProperty("function_id") @Nullable String functionId,
    @JsonProperty("right") @NonNull String right) {
}
