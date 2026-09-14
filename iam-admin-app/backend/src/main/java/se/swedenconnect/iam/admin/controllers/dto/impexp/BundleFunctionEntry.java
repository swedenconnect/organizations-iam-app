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
 * A single function definition inside an {@link ImportExportBundle}.
 *
 * <p>The display name and the description are localized values, carried as one key per language
 * tagged after a {@code #}, the same form the Keycloak group attributes use. Swedish and English
 * are the only tags; a key tagged with any other language is ignored.</p>
 *
 * @author PF Plars
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BundleFunctionEntry(
    @JsonProperty("id") @NonNull String id,
    @JsonProperty("name#sv") @Nullable String nameSv,
    @JsonProperty("name#en") @Nullable String nameEn,
    @JsonProperty("description#sv") @Nullable String descriptionSv,
    @JsonProperty("description#en") @Nullable String descriptionEn) {
}
