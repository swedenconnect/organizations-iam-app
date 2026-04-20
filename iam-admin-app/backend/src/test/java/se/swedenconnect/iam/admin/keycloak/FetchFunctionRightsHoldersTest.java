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
package se.swedenconnect.iam.admin.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.model.RightsHolderEntry;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;

/**
 * Unit tests for {@link KeycloakAdminClient#fetchFunctionRightsHolders}.
 *
 * <p>Uses a spy on KeycloakAdminClient to stub out the internal Keycloak calls
 * (resolveOrgGroupId, fetchGroupChildren, fetchGroupMembers) without requiring
 * a real Keycloak server.</p>
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FetchFunctionRightsHoldersTest {

  @Mock
  private OAuth2AuthorizedClientManager authorizedClientManager;

  private KeycloakAdminClient client;

  private static final String ORG = "5590026042";
  private static final String FUNC = "demo";

  @BeforeEach
  void setUp() {
    final IamAdminProperties props = new IamAdminProperties();
    props.setAdminApiBase("http://localhost:8080/admin/realms/orgiam");

    // Mock token acquisition
    final OAuth2AuthorizedClient authorizedClient = mock(OAuth2AuthorizedClient.class);
    final OAuth2AccessToken accessToken = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "fake-token", Instant.now(), Instant.now().plusSeconds(300));
    lenient().when(authorizedClient.getAccessToken()).thenReturn(accessToken);
    lenient().when(this.authorizedClientManager.authorize(any())).thenReturn(authorizedClient);

    this.client = spy(new KeycloakAdminClient(
        this.authorizedClientManager, RestClient.builder(), props));
  }

  @Test
  void dedup_highestRightWins() {
    // User appears in both org _admin and func _read — admin wins
    setupGroups(
        List.of(user("u1", "Anna", "Andersson", "197001011234")), // org _admin
        List.of(), // org _write
        List.of(), // org _read
        List.of(), // func _admin
        List.of(), // func _write
        List.of(user("u1", "Anna", "Andersson", "197001011234"))  // func _read
    );

    final List<RightsHolderEntry> result = this.client.fetchFunctionRightsHolders(ORG, FUNC);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().right()).isEqualTo("admin");
    assertThat(result.getFirst().scope()).isEqualTo("organization");
  }

  @Test
  void dedup_sameLevelFunctionScopeWins() {
    // User has write at both org and function level — function wins
    setupGroups(
        List.of(), // org _admin
        List.of(user("u1", "Bertil", "Bengtsson", "198505050505")), // org _write
        List.of(), // org _read
        List.of(), // func _admin
        List.of(user("u1", "Bertil", "Bengtsson", "198505050505")), // func _write
        List.of()  // func _read
    );

    final List<RightsHolderEntry> result = this.client.fetchFunctionRightsHolders(ORG, FUNC);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().right()).isEqualTo("write");
    assertThat(result.getFirst().scope()).isEqualTo("function");
  }

  @Test
  void multipleUsers_sortedByRightThenName() {
    setupGroups(
        List.of(), // org _admin
        List.of(), // org _write
        List.of(user("u2", "Zebra", "Zulu", null)), // org _read
        List.of(user("u1", "Anna", "Admin", "197001011234")), // func _admin
        List.of(), // func _write
        List.of(user("u3", "Charlie", "Charlsson", "199901019999"))  // func _read
    );

    final List<RightsHolderEntry> result = this.client.fetchFunctionRightsHolders(ORG, FUNC);

    assertThat(result).hasSize(3);
    // First: admin
    assertThat(result.get(0).right()).isEqualTo("admin");
    assertThat(result.get(0).name()).isEqualTo("Anna Admin");
    // Second and third: read sorted by name
    assertThat(result.get(1).right()).isEqualTo("read");
    assertThat(result.get(1).name()).isEqualTo("Charlie Charlsson");
    assertThat(result.get(2).right()).isEqualTo("read");
    assertThat(result.get(2).name()).isEqualTo("Zebra Zulu");
  }

  @Test
  void emptyGroups_returnsEmptyList() {
    setupGroups(
        List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of()
    );

    final List<RightsHolderEntry> result = this.client.fetchFunctionRightsHolders(ORG, FUNC);

    assertThat(result).isEmpty();
  }

  @Test
  void userOnlyAtOrgScope() {
    setupGroups(
        List.of(), // org _admin
        List.of(user("u1", "Anna", null, "197001011234")), // org _write
        List.of(), // org _read
        List.of(), // func _admin
        List.of(), // func _write
        List.of()  // func _read
    );

    final List<RightsHolderEntry> result = this.client.fetchFunctionRightsHolders(ORG, FUNC);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().scope()).isEqualTo("organization");
    assertThat(result.getFirst().right()).isEqualTo("write");
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private void setupGroups(
      final List<UserInfo> orgAdmin,
      final List<UserInfo> orgWrite,
      final List<UserInfo> orgRead,
      final List<UserInfo> funcAdmin,
      final List<UserInfo> funcWrite,
      final List<UserInfo> funcRead) {

    final String orgGroupId = "org-group-id";
    final String funcGroupId = "func-group-id";
    final String orgAdminId = "org-admin-gid";
    final String orgWriteId = "org-write-gid";
    final String orgReadId = "org-read-gid";
    final String funcAdminId = "func-admin-gid";
    final String funcWriteId = "func-write-gid";
    final String funcReadId = "func-read-gid";

    // Stub findTopLevelGroupId("orgs")
    doReturn("orgs-top-level-id").when(this.client).findTopLevelGroupId("orgs");

    // Stub fetchGroupChildren for the orgs top-level → returns org group
    doReturn(List.of(Map.<String, Object>of("id", orgGroupId, "name", ORG)))
        .when(this.client).fetchGroupChildren("orgs-top-level-id");

    // Stub fetchGroupChildren for the org group → returns org-level right groups + function sub-group
    doReturn(List.of(
        Map.<String, Object>of("id", orgAdminId, "name", "_admin"),
        Map.<String, Object>of("id", orgWriteId, "name", "_write"),
        Map.<String, Object>of("id", orgReadId, "name", "_read"),
        Map.<String, Object>of("id", funcGroupId, "name", FUNC)
    )).when(this.client).fetchGroupChildren(orgGroupId);

    // Stub fetchGroupChildren for the function sub-group
    doReturn(List.of(
        Map.<String, Object>of("id", funcAdminId, "name", "_admin"),
        Map.<String, Object>of("id", funcWriteId, "name", "_write"),
        Map.<String, Object>of("id", funcReadId, "name", "_read")
    )).when(this.client).fetchGroupChildren(funcGroupId);

    // Stub fetchGroupMembers for each group
    doReturn(orgAdmin).when(this.client).fetchGroupMembers(orgAdminId);
    doReturn(orgWrite).when(this.client).fetchGroupMembers(orgWriteId);
    doReturn(orgRead).when(this.client).fetchGroupMembers(orgReadId);
    doReturn(funcAdmin).when(this.client).fetchGroupMembers(funcAdminId);
    doReturn(funcWrite).when(this.client).fetchGroupMembers(funcWriteId);
    doReturn(funcRead).when(this.client).fetchGroupMembers(funcReadId);
  }

  private static UserInfo user(
      final String id, final String firstName, final String lastName, final String pin) {
    return new UserInfo(id, "user-" + id, firstName, lastName, null, pin, null, false, List.of());
  }

}
