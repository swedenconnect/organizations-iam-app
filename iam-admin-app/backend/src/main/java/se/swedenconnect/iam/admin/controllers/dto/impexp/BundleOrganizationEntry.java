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

import java.util.List;

/**
 * A single organization inside an {@link ImportExportBundle}, including the identifiers of the
 * functions it has attached.
 *
 * <p>The display name is a localized value, carried as one key per language tagged after a
 * {@code #}, the same form the Keycloak group attributes use. Swedish and English are the only
 * tags; a key tagged with any other language is ignored. The legal name is the name registered
 * at Bolagsverket, which is not localized, so {@code legal_name} is a plain untagged key.</p>
 *
 * @author PF Plars
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BundleOrganizationEntry(
    @JsonProperty("org_identifier") @NonNull String orgIdentifier,
    @JsonProperty("legal_name") @NonNull String legalName,
    @JsonProperty("name#sv") @Nullable String nameSv,
    @JsonProperty("name#en") @Nullable String nameEn,
    @JsonProperty("contact_email") @Nullable String contactEmail,
    @JsonProperty("contact_phone") @Nullable String contactPhone,
    @JsonProperty("attached_functions") @NonNull List<String> attachedFunctions) {
}
