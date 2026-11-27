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
package se.swedenconnect.iam.admin.service;

import org.junit.jupiter.api.Test;
import se.swedenconnect.iam.admin.controllers.dto.impexp.BundleFunctionEntry;
import se.swedenconnect.iam.admin.controllers.dto.impexp.BundleOrganizationEntry;
import se.swedenconnect.iam.admin.controllers.dto.impexp.BundleUserEntry;
import se.swedenconnect.iam.admin.controllers.dto.impexp.BundleUserRightEntry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dry-run batch is held in the HTTP session between the dry run and the confirmation, so it has
 * to survive the JDK serialization Spring Session writes a session to Redis with. It is reached
 * from this package only, which is why it is covered here rather than in
 * {@code SessionAttributeSerializationTest}.
 *
 * @author Per Fredrik Plars
 */
class PendingImportBatchSerializationTest {

  @Test
  void theDryRunBatchSurvivesSerialization() {
    final PendingImportBatch batch = new PendingImportBatch("batch-1", "owner-1",
        Instant.parse("2026-11-24T10:00:00Z"),
        List.of(new BundleFunctionEntry("walletreg", "Plånboksregistrering",
            "Wallet registration", null, null)),
        List.of(new BundleOrganizationEntry("2021006883", "Myndigheten för digital förvaltning",
            "DIGG", "DIGG", "kontakt@digg.se", "+46101234567", List.of("walletreg"))),
        List.of(new BundleUserEntry("Klara Karlsson", null, "klara@example.com", null, null, null,
            null, null, List.of(new BundleUserRightEntry("2021006883", "walletreg", "admin")))));

    final PendingImportBatch restored = roundTrip(batch);

    assertThat(restored).isEqualTo(batch);
  }

  /** Writes the object out with JDK serialization and reads it back, as Redis would. */
  @SuppressWarnings("unchecked")
  private static <T> T roundTrip(final T object) {
    try {
      final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
        out.writeObject(object);
      }
      try (ObjectInputStream in =
          new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        return (T) in.readObject();
      }
    }
    catch (final Exception e) {
      throw new AssertionError(
          "%s does not survive the serialization Spring Session uses for Redis"
              .formatted(object.getClass().getName()), e);
    }
  }

}
