// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.SERVER_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.DomainBase;
import google.registry.model.registrar.Registrar.Type;
import google.registry.model.registry.RegistryLockDao;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.FakeClock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link UnlockDomainCommand}. */
public class UnlockDomainCommandTest extends CommandTestCase<UnlockDomainCommand> {

  @Rule
  public final JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().buildIntegrationWithCoverageRule();

  @Before
  public void before() {
    persistNewRegistrar("adminreg", "Admin Registrar", Type.REAL, 693L);
    createTld("tld");
    command.registryAdminClientId = "adminreg";
    command.clock = new FakeClock();
  }

  private DomainBase persistLockedDomain(String domainName, String registrarId) {
    DomainBase domain = persistResource(newDomainBase(domainName));
    RegistryLock lock =
        DomainLockUtils.createRegistryLockRequest(
            domainName, registrarId, null, true, command.clock);
    DomainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true, command.clock);
    return reloadResource(domain);
  }

  @Test
  public void testSuccess_unlocksDomain() throws Exception {
    DomainBase domain = persistLockedDomain("example.tld", "NewRegistrar");
    runCommandForced("--client=NewRegistrar", "example.tld");
    assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  public void testSuccess_partiallyUpdatesStatuses() throws Exception {
    DomainBase domain = persistLockedDomain("example.tld", "NewRegistrar");
    domain =
        persistResource(
            domain
                .asBuilder()
                .setStatusValues(
                    ImmutableSet.of(SERVER_DELETE_PROHIBITED, SERVER_UPDATE_PROHIBITED))
                .build());
    runCommandForced("--client=NewRegistrar", "example.tld");
    assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  public void testSuccess_manyDomains() throws Exception {
    // Create 26 domains -- one more than the number of entity groups allowed in a transaction (in
    // case that was going to be the failure point).
    List<DomainBase> domains = new ArrayList<>();
    for (int n = 0; n < 26; n++) {
      String domain = String.format("domain%d.tld", n);
      domains.add(persistLockedDomain(domain, "NewRegistrar"));
    }
    runCommandForced(
        ImmutableList.<String>builder()
            .add("--client=NewRegistrar")
            .addAll(
                domains.stream()
                    .map(DomainBase::getFullyQualifiedDomainName)
                    .collect(Collectors.toList()))
            .build());
    for (DomainBase domain : domains) {
      assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    }
  }

  @Test
  public void testFailure_domainDoesntExist() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--client=NewRegistrar", "missing.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Domain 'missing.tld' does not exist or is deleted");
  }

  @Test
  public void testSuccess_alreadyUnlockedDomain_performsNoAction() throws Exception {
    DomainBase domain = persistActiveDomain("example.tld");
    runCommandForced("--client=NewRegistrar", "example.tld");
    assertThat(reloadResource(domain)).isEqualTo(domain);
  }

  @Test
  public void testSuccess_defaultsToAdminRegistrar_ifUnspecified() throws Exception {
    DomainBase domain = persistLockedDomain("example.tld", "NewRegistrar");
    runCommandForced("example.tld");
    assertThat(RegistryLockDao.getMostRecentByRepoId(domain.getRepoId()).get().getRegistrarId())
        .isEqualTo("adminreg");
  }

  @Test
  public void testFailure_duplicateDomainsAreSpecified() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--client=NewRegistrar", "dupe.tld", "dupe.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate domain arguments found: 'dupe.tld'");
  }
}
