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
package se.swedenconnect.iam.demo.service.storage;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import se.swedenconnect.iam.demo.service.model.ContactData;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for organization contact data.
 *
 * @author Martin Lindström
 */
@Component
public class ContactDataStore {

  private final ConcurrentHashMap<String, ContactData> store = new ConcurrentHashMap<>();

  /**
   * Returns the stored {@link ContactData} for the given organization, or a {@code ContactData}
   * with all-null fields if no entry exists.
   *
   * @param orgId the organization identifier; must not be null
   * @return the contact data; never null
   */
  public @NonNull ContactData get(final @NonNull String orgId) {
    return this.store.getOrDefault(orgId, new ContactData(null, null, null));
  }

  /**
   * Stores or replaces the {@link ContactData} for the given organization.
   *
   * @param orgId the organization identifier; must not be null
   * @param data the contact data to store; must not be null
   */
  public void put(final @NonNull String orgId, final @NonNull ContactData data) {
    this.store.put(orgId, data);
  }

}
