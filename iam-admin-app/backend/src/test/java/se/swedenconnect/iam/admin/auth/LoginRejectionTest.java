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
package se.swedenconnect.iam.admin.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the mapping from a rejection reason to a message code and its arguments.
 *
 * @author Martin Lindström
 */
class LoginRejectionTest {

  @Test
  void noOrganizationalRights_hasNoArguments() {
    final LoginRejection rejection = LoginRejection.noOrganizationalRights();

    assertThat(rejection.code()).isEqualTo(LoginRejection.NO_ORGANIZATIONAL_RIGHTS);
    assertThat(rejection.arguments()).isEmpty();
  }

  @Test
  void unconstrained_mapsToNoAdminRight() {
    final LoginRejection rejection = LoginRejection.insufficientAdminRights(null, null);

    assertThat(rejection.code()).isEqualTo(LoginRejection.NO_ADMIN_RIGHT);
    assertThat(rejection.arguments()).isEmpty();
  }

  @Test
  void orgConstrained_mapsToNoAdminRightInOrganization() {
    final LoginRejection rejection = LoginRejection.insufficientAdminRights("2021006883", null);

    assertThat(rejection.code()).isEqualTo(LoginRejection.NO_ADMIN_RIGHT_IN_ORGANIZATION);
    assertThat(rejection.arguments()).containsExactly("2021006883");
  }

  @Test
  void functionConstrained_mapsToNoAdminRightForFunction() {
    final LoginRejection rejection = LoginRejection.insufficientAdminRights(null, "walletreg");

    assertThat(rejection.code()).isEqualTo(LoginRejection.NO_ADMIN_RIGHT_FOR_FUNCTION);
    assertThat(rejection.arguments()).containsExactly("walletreg");
  }

  @Test
  void bothConstrained_mapsToNoAdminRightForFunctionInOrganization_functionFirst() {
    final LoginRejection rejection = LoginRejection.insufficientAdminRights("2021006883", "walletreg");

    assertThat(rejection.code()).isEqualTo(LoginRejection.NO_ADMIN_RIGHT_FOR_FUNCTION_IN_ORGANIZATION);
    assertThat(rejection.arguments()).containsExactly("walletreg", "2021006883");
  }

}
