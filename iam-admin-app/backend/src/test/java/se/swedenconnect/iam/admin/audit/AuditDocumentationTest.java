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
package se.swedenconnect.iam.admin.audit;

import org.junit.jupiter.api.Test;
import se.swedenconnect.spring.audit.AuditType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@code docs/audit-events.md} documents exactly the audit event types and operation
 * values the code produces.
 *
 * @author Martin Lindström
 */
class AuditDocumentationTest {

  private static final Path DOCUMENT = Path.of("../../docs/audit-events.md");

  /** The event types declared as constants are all listed in {@link IamAuditTypes#ALL}. */
  @Test
  void allTypesAreListed() throws Exception {
    final List<AuditType> declared = Arrays.stream(IamAuditTypes.class.getFields())
        .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == AuditType.class)
        .map(AuditDocumentationTest::value)
        .map(AuditType.class::cast)
        .toList();

    assertThat(IamAuditTypes.ALL).containsExactlyInAnyOrderElementsOf(declared);
  }

  /** Every type the application produces has a section, and every section is a type it produces. */
  @Test
  void documentedTypesMatchTheCode() throws Exception {
    final String document = Files.readString(DOCUMENT);
    final Matcher matcher = Pattern.compile("\\*\\*Type:\\*\\* `([a-z_]+)`").matcher(document);
    final List<String> documented = matcher.results().map(m -> m.group(1)).toList();

    assertThat(documented).containsExactlyInAnyOrderElementsOf(
        IamAuditTypes.ALL.stream().map(AuditType::type).toList());
    for (final String type : documented) {
      assertThat(document).as("anchor for " + type).contains("<a name=\"" + type + "\"></a>");
    }
  }

  /** Every operation name and reason is listed. */
  @Test
  void operationValuesAreDocumented() throws Exception {
    final String document = Files.readString(DOCUMENT);
    for (final Field field : AuditOperations.class.getFields()) {
      final String value = (String) field.get(null);
      assertThat(document).as(field.getName()).contains("| `" + value + "` |");
    }
  }

  private static Object value(final Field field) {
    try {
      return field.get(null);
    }
    catch (final IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

}
