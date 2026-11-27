package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.test.mock.TestStore;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.scripting.objects.File;

@IvyProcessTest
class TestVersionStore {

  private VersionStore store;

  @BeforeEach
  void setup() {
    TestStore.clear();
    store = VersionStore.current();
  }

  @Test
  void versionsAreFilesOfTheApplication() throws Exception {
    String dmn = discount(15);
    store.register("discount", api(dmn));

    var folder = new File("rule-engine/discount", false).getJavaFile().toPath();
    assertThat(store.directory()).isEqualTo(folder.getParent().toAbsolutePath());
    assertThat(Files.readString(folder.resolve("1.dmn"))).isEqualTo(dmn);
    assertThat(Files.readString(folder.resolve("versions.json")))
        .containsPattern("\"model\"\s*:\s*\"discount\"").containsPattern("\"status\"\s*:\s*\"ACTIVE\"");
    assertThat(store.models()).containsExactly("discount");
    assertThat(store.content("discount", 1)).isEqualTo(dmn);
  }

  @Test
  void whatIsStored_isReadBackTheSame() {
    var effective = OffsetDateTime.now().plusDays(3).truncatedTo(ChronoUnit.SECONDS);
    store.register("discount", api(discount(10)));
    var registered = store.register("discount", RegisterVersionRequest.builder()
        .content(discount(20)).author("rule.owner").description("Gold 20").source(VersionSource.PORTAL)
        .effectiveDate(effective).build());

    var read = store.version("discount", 2);
    assertThat(read.status()).isEqualTo(VersionStatus.APPROVED);
    assertThat(read.source()).isEqualTo(VersionSource.PORTAL);
    assertThat(read.author()).isEqualTo("rule.owner");
    assertThat(read.description()).isEqualTo("Gold 20");
    assertThat(read.effectiveDate().toInstant()).isEqualTo(effective.toInstant());
    assertThat(read.createdAt().toInstant()).isEqualTo(registered.createdAt().toInstant());
    assertThat(store.content("discount", 2)).isEqualTo(discount(20));
    assertThat(read.created()).as("only answered on registration").isNull();
  }

  @Test
  void sameContent_isNotStoredTwice() {
    assertThat(store.register("discount", api(discount(15))).created()).isTrue();

    var again = store.register("discount", api(discount(15)));
    var otherLineEndings = store.register("discount", api(discount(15).replace("\n", "\r\n")));

    assertThat(again.created()).isFalse();
    assertThat(again.version()).isEqualTo(1);
    assertThat(otherLineEndings.version()).as("line endings do not make a new version").isEqualTo(1);
    assertThat(store.versions("discount")).hasSize(1);
  }

  @Test
  void firstVersionGoesLive_laterOnesAreDrafts() {
    assertThat(store.register("discount", api(discount(15))).status()).isEqualTo(VersionStatus.ACTIVE);
    assertThat(store.register("discount", api(discount(20))).status()).isEqualTo(VersionStatus.DRAFT);
    assertThat(store.register("discount", api(discount(25))).status()).isEqualTo(VersionStatus.DRAFT);

    assertThat(store.versions("discount")).extracting(ModelVersion::version).containsExactly(3, 2, 1);
    assertThat(store.active("discount").version()).isEqualTo(1);
  }

  @Test
  void projectRedeployment_replacesProjectVersion() {
    store.register("discount", project(discount(10)));
    assertThat(store.register("discount", project(discount(20))).status()).isEqualTo(VersionStatus.ACTIVE);
    assertThat(store.version("discount", 1).status()).isEqualTo(VersionStatus.RETIRED);

    // deploying an older state of the project again makes that state live again
    var older = store.register("discount", project(discount(10)));
    assertThat(older.version()).isEqualTo(1);
    assertThat(older.status()).isEqualTo(VersionStatus.ACTIVE);
  }

  @Test
  void projectRedeployment_doesNotOverwriteVersionActivatedFromPortal() {
    store.register("discount", project(discount(10)));
    var portal = store.register("discount", RegisterVersionRequest.builder()
        .content(discount(20)).author("rule.owner").source(VersionSource.PORTAL).build());
    assertThat(portal.status()).isEqualTo(VersionStatus.DRAFT);
    assertThat(store.approve("discount", 2).status()).isEqualTo(VersionStatus.APPROVED);
    var active = store.activate("discount", 2, "rule.approver", null);
    assertThat(active.status()).isEqualTo(VersionStatus.ACTIVE);
    assertThat(active.activatedBy()).isEqualTo("rule.approver");
    assertThat(active.previousVersion()).isEqualTo(1);

    // the project is deployed again with a changed file: it is stored, but does not go live
    var changed = store.register("discount", project(discount(30)));
    assertThat(changed.version()).isEqualTo(3);
    assertThat(changed.status()).isEqualTo(VersionStatus.DRAFT);
    // and neither does the unchanged file of the initial deployment
    var unchanged = store.register("discount", project(discount(10)));
    assertThat(unchanged.version()).isEqualTo(1);
    assertThat(unchanged.status()).isEqualTo(VersionStatus.RETIRED);
    assertThat(store.active("discount").version()).isEqualTo(2);
  }

  @Test
  void rollback_activatesThePreviousVersion() throws InterruptedException {
    store.register("discount", api(discount(10)));
    store.register("discount", activated(discount(20)));
    store.register("discount", activated(discount(30)));
    assertThat(store.active("discount").version()).isEqualTo(3);
    var firstLive = store.version("discount", 2).effectiveDate();
    Thread.sleep(20);

    var rolledBack = store.rollback("discount", "rule.approver");
    assertThat(rolledBack.version()).isEqualTo(2);
    assertThat(rolledBack.effectiveDate()).as("live again from the rollback").isAfter(firstLive);
    assertThat(rolledBack.activatedBy()).isEqualTo("rule.approver");
    assertThat(store.version("discount", 3).status()).isEqualTo(VersionStatus.RETIRED);
    assertThat(store.version("discount", 3).effectiveDate()).as("a retired version keeps when it was live").isNotNull();
    assertThat(store.rollback("discount", "rule.approver").version()).as("repeated rollbacks walk back").isEqualTo(1);

    assertThatThrownBy(() -> store.rollback("discount", "rule.approver"))
        .isInstanceOfSatisfying(RuleEngineApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_STATE));
  }

  @Test
  void versionGoesLive_atItsEffectiveDate() throws InterruptedException {
    store.register("discount", api(discount(10)));
    store.register("discount", api(discount(20)));
    var effective = OffsetDateTime.now().plusSeconds(1);

    assertThat(store.activate("discount", 2, "rule.approver", effective).status()).isEqualTo(VersionStatus.APPROVED);
    assertThat(store.active("discount").version()).isEqualTo(1);

    Thread.sleep(1200);
    var active = store.active("discount");
    assertThat(active.version()).as("the first read after the effective date activates it").isEqualTo(2);
    assertThat(active.activatedBy()).isEqualTo("rule.approver");
    assertThat(active.effectiveDate().toInstant()).as("live from its effective date, not from the read")
        .isEqualTo(effective.toInstant());
    assertThat(store.version("discount", 1).status()).isEqualTo(VersionStatus.RETIRED);
  }

  @Test
  void retire_onlyVersionsThatAreNotActive() {
    store.register("discount", api(discount(10)));
    store.register("discount", api(discount(20)));

    assertThatThrownBy(() -> store.retire("discount", 1)).isInstanceOfSatisfying(RuleEngineApiException.class,
        ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_STATE));
    assertThat(store.retire("discount", 2).status()).isEqualTo(VersionStatus.RETIRED);
    assertThatThrownBy(() -> store.retire("discount", 9)).isInstanceOfSatisfying(RuleEngineApiException.class,
        ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.VERSION_NOT_FOUND));
    assertThatThrownBy(() -> store.approve("discount", 2)).as("only a draft can be approved")
        .isInstanceOfSatisfying(RuleEngineApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_STATE));
  }

  @Test
  void unknownModels_andModelsWithoutActiveVersion() {
    assertThatThrownBy(() -> store.active("discount")).isInstanceOfSatisfying(RuleEngineApiException.class,
        ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.MODEL_NOT_FOUND));

    store.register("discount", RegisterVersionRequest.builder().content(discount(10)).activate(false).build());
    assertThatThrownBy(() -> store.active("discount")).isInstanceOfSatisfying(RuleEngineApiException.class,
        ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.NO_ACTIVE_VERSION));
  }

  @Test
  void invalidRequests_areRefused() {
    for (String name : new String[] {"bad name!", "../other", "dots.", ""}) {
      assertThatThrownBy(() -> store.register(name, api(discount(10)))).as(name)
          .isInstanceOfSatisfying(RuleEngineApiException.class,
              ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_REQUEST));
    }
    assertThatThrownBy(() -> store.register("discount", api(" "))).isInstanceOfSatisfying(RuleEngineApiException.class,
        ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_REQUEST));
    assertThat(store.models()).isEmpty();
  }

  @Test
  void delete_removesTheModelWithAllVersions() {
    store.register("discount", api(discount(10)));
    store.register("discount", api(discount(20)));

    store.delete("discount");

    assertThat(store.exists("discount")).isFalse();
    assertThat(store.models()).isEmpty();
    assertThat(store.register("discount", api(discount(30))).version()).as("starts again at 1").isEqualTo(1);
  }

  private static RegisterVersionRequest api(String dmn) {
    return RegisterVersionRequest.builder().content(dmn).author("test").build();
  }

  private static RegisterVersionRequest project(String dmn) {
    return RegisterVersionRequest.builder().content(dmn).author("test").source(VersionSource.PROJECT).build();
  }

  private static RegisterVersionRequest activated(String dmn) {
    return RegisterVersionRequest.builder().content(dmn).author("test").activate(true).build();
  }

  private static String discount(int gold) {
    return TestStore.dmn("discount", "discount", "purchaseAmount", "type").replace("<decision ",
        "<!-- gold " + gold + " -->\n  <decision ");
  }
}
