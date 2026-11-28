package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.demo.DiscountTable;
import com.axonivy.utils.rule.engine.demo.Invoice;
import com.axonivy.utils.rule.engine.demo.InvoiceSamples;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.portal.RuleManagement;
import com.axonivy.utils.rule.engine.test.TestData;
import com.axonivy.utils.rule.engine.test.mock.TestStore;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.bpm.engine.client.BpmClient;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;

/**
 * End to end against the real DMN engine in a container, the Apache KIE JIT runner: Ivy process, Business Rule Task,
 * the versions in the file area of the application and REST. The discount of the demo invoice is decided by
 * dmn/invoice-discount.dmn of rule-engine-demo. Run with <code>mvn verify -Pcontainer.test</code>.
 */
@IvyProcessTest
class RuleEngineContainerTest {

  private static final String DEMO = DiscountTable.MODEL;
  /** The discount of the rule "Silver customer" in dmn/invoice-discount.dmn, however the editor formatted it. */
  private static final Pattern SILVER =
      Pattern.compile("(?<=<outputEntry id=\"_discount_rule_6_out_1\">\\s{0,40}<text>)2(?=</text>)");

  @SuppressWarnings("resource")
  static GenericContainer<?> engine = new GenericContainer<>(
      System.getProperty("rule.engine.image", "apache/incubator-kie-kogito-jit-runner:10.2.0"))
      .withExposedPorts(8080)
      .waitingFor(Wait.forHttp("/q/health/ready").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

  @BeforeAll
  public static void startEngine() {
    engine.start();
  }

  @AfterAll
  public static void stopEngine() {
    engine.stop();
  }

  @BeforeEach
  public void setup(AppFixture fixture) {
    fixture.var(Var.URL, "http://" + engine.getHost() + ":" + engine.getMappedPort(8080));
    fixture.var(Var.AUTO_DEPLOY, "true");
    TestStore.clear();
    ProjectDmnFiles.refresh();
  }

  @Test
  void demoInvoice_isDecidedByTheTable(BpmClient client) {
    var execution = TestInvoicePricing.execute(client, InvoiceSamples.standard());
    Invoice invoice = execution.subResult().param("invoice", Invoice.class);

    assertThat(execution.subResult().param("errorMessage", String.class)).isNullOrEmpty();
    assertThat(invoice.getOrderAmount()).isEqualByComparingTo("9200.00");
    assertThat(invoice.getDiscountPercent()).isEqualByComparingTo("2");
    assertThat(invoice.getDiscountReason()).isEqualTo("Silver customer");
    assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("184.00");
    assertThat(invoice.getTotal()).isEqualByComparingTo("9016.00");

    Integer decided = TestBusinessRuleTask.decidedBy(execution, DEMO);
    assertThat(decided).as("the version that decided is stored on the case").isNotNull().isPositive();
    var version = RuleEngine.versions(DEMO).stream().filter(ModelVersion::isActive).findFirst().orElseThrow();
    assertThat(version.version()).isEqualTo(decided);
    assertThat(version.source()).as("stored from the project on its first use").isEqualTo(VersionSource.PROJECT);
    assertThat(version.author()).as("the application and its version").matches(".+ v\\d+");
    assertThat(version.description()).contains("rule-engine-demo");
    assertThat(DiscountTable.of(decided).matching(invoice)).as("the rule the page marks").isEqualTo(6);
  }

  @Test
  void demoInvoice_otherTier_otherRule(BpmClient client) {
    assertThat(discount(client, "Standard", 4)).isEqualTo("0 No discount 9200.00");
    assertThat(discount(client, "Gold", 4)).isEqualTo("5 Gold customer 8740.00");
    assertThat(discount(client, "Platinum", 4)).isEqualTo("8 Platinum customer 8464.00");
    assertThat(discount(client, "Silver", 5)).as("a fifth laptop: 10,400").isEqualTo("4 Silver, order of 10,000 or more 9984.00");
    assertThat(discount(client, "Gold", 5)).isEqualTo("8 Gold, order of 10,000 or more 9568.00");
  }

  /**
   * In the Portal a rule owner adds an input the .dmn file of the project does not have. The same data, the same
   * process, no deployment: it is decided otherwise.
   */
  @Test
  @SuppressWarnings("unchecked")
  void inputAddedInThePortal_decidesTheSameDataOtherwise(BpmClient client) throws IOException {
    TestData before = TestBusinessRuleTask.run(client, "demoDiscount", TestBusinessRuleTask.sampleData());
    assertThat(before.getErrorMessage()).isNull();
    assertThat(before.getDiscount().intValue()).as("Silver, 1500: every large purchase").isEqualTo(10);

    String withSegment = Files.readString(Project.current().directory()
        .resolve("src_test/com/axonivy/utils/rule/engine/scenario/discount-portal.dmn.xml"));
    var saved = RuleManagement.save("discount", withSegment, "admin", "Customers of the segment GOLD", true);
    assertThat(saved.error()).as("the DMN engine refused the model").isNull();
    var version = (Map<String, Object>) saved.result();
    assertThat(version).containsEntry("status", "ACTIVE").containsEntry("source", "PORTAL").containsEntry("author", "admin");

    var afterExecution = TestBusinessRuleTask.execute(client, "demoDiscount", TestBusinessRuleTask.sampleData());
    TestData after = afterExecution.subResult().param("data", TestData.class);
    assertThat(after.getErrorMessage()).isNull();
    assertThat(after.getDiscount().intValue()).as("segment GOLD, read from in.customer.segment").isEqualTo(25);
    assertThat(TestBusinessRuleTask.decidedBy(afterExecution, "discount")).isEqualTo(version.get("version"));
    RuleEngine.rollback("discount", "admin");
  }

  /**
   * A rule owner adds the input customer and a key account rule at the top of the table. The invoice has a field
   * customer, which the file of the project does not read. The same invoice, the same process, no deployment: it
   * costs less.
   */
  @Test
  @SuppressWarnings("unchecked")
  void keyAccountAddedInThePortal_pricesTheSameInvoiceLower(BpmClient client) throws IOException {
    assertThat(TestInvoicePricing.price(client, InvoiceSamples.standard()).getDiscountReason()).isEqualTo("Silver customer");

    String keyAccount = Files.readString(Project.current().directory()
        .resolve("src_test/com/axonivy/utils/rule/engine/scenario/invoice-discount-key-account.dmn.xml"));
    var saved = RuleManagement.save(DEMO, keyAccount, "admin", "Key account discount for Bluebird Architects", true);
    assertThat(saved.error()).as("the DMN engine refused the model").isNull();
    var version = (Map<String, Object>) saved.result();
    try {
      var execution = TestInvoicePricing.execute(client, InvoiceSamples.standard());
      Invoice invoice = execution.subResult().param("invoice", Invoice.class);
      assertThat(execution.subResult().param("errorMessage", String.class)).isNullOrEmpty();
      assertThat(invoice.getDiscountReason()).isEqualTo("Key account: framework contract");
      assertThat(invoice.getDiscountPercent()).isEqualByComparingTo("10");
      assertThat(invoice.getTotal()).isEqualByComparingTo("8280.00");
      assertThat(TestBusinessRuleTask.decidedBy(execution, DEMO)).isEqualTo(version.get("version"));

      Invoice other = InvoiceSamples.standard();
      other.setCustomer("Another customer");
      assertThat(TestInvoicePricing.price(client, other).getDiscountReason()).isEqualTo("Silver customer");
    } finally {
      RuleEngine.rollback(DEMO, "admin");
    }
    assertThat(TestInvoicePricing.price(client, InvoiceSamples.standard()).getTotal()).as("rolled back")
        .isEqualByComparingTo("9016.00");
  }

  /** The story of the demo: a rule owner changes a cell, the same invoice is priced otherwise, and back. */
  @Test
  void versionActivatedFromThePortal_isLiveAtOnce_andSurvivesRedeployment(BpmClient client) {
    var initialExecution = TestInvoicePricing.execute(client, InvoiceSamples.standard());
    assertThat(silverDiscount(initialExecution.subResult().param("invoice", Invoice.class))).isEqualTo(2);
    int projectVersion = TestBusinessRuleTask.decidedBy(initialExecution, DEMO);

    // a rule owner raises the discount of Silver customers
    String current = VersionStore.current().content(DEMO, projectVersion);
    String changed = SILVER.matcher(current).replaceFirst("3");
    assertThat(changed).isNotEqualTo(current);
    ModelVersion draft = RuleEngine.register(DEMO, changed, "rule.editor", "Silver customers get 3%");
    assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
    assertThat(silverDiscount(client)).as("a draft is not live").isEqualTo(2);

    assertThat(RuleEngine.approve(DEMO, draft.version()).status()).isEqualTo(VersionStatus.APPROVED);
    ModelVersion active = RuleEngine.activate(DEMO, draft.version(), "rule.approver");
    assertThat(active.status()).isEqualTo(VersionStatus.ACTIVE);
    assertThat(active.activatedBy()).isEqualTo("rule.approver");

    var activationExecution = TestInvoicePricing.execute(client, InvoiceSamples.standard());
    Invoice afterActivation = activationExecution.subResult().param("invoice", Invoice.class);
    assertThat(silverDiscount(afterActivation)).as("live without restart or deployment").isEqualTo(3);
    assertThat(afterActivation.getTotal()).isEqualByComparingTo("8924.00");
    assertThat(TestBusinessRuleTask.decidedBy(activationExecution, DEMO)).isEqualTo(draft.version());
    assertThat(DiscountTable.of(draft.version()).rules().get(5).getOutputs()).as("the table the page shows")
        .containsExactly("3", "\"Silver customer\"");

    // the project is deployed again: its file is stored, but the version of the Portal stays active
    ModelDeployer.reset();
    var deployed = RuleEngine.deploy(Project.current());
    assertThat(deployed).extracting(ModelVersion::model).contains(DEMO, "discount");
    assertThat(silverDiscount(client)).as("redeployment does not overwrite the version of the Portal").isEqualTo(3);

    // rollback: the previous version is active again
    ModelVersion rolledBack = RuleEngine.rollback(DEMO, "rule.approver");
    assertThat(rolledBack.version()).isEqualTo(projectVersion);
    assertThat(silverDiscount(client)).isEqualTo(2);
    assertThat(RuleEngine.versions(DEMO)).filteredOn(ModelVersion::isActive).hasSize(1);
  }

  @Test
  void versionWithEffectiveDate_goesLiveLater() {
    var store = VersionStore.current();
    String model = "effective-" + System.nanoTime();
    String dmn = ProjectDmnFiles.load(ProjectDmnFiles.find(Project.current(), "discount").orElseThrow()).content();
    ModelDeployer.register(RuleEngine.client(), store, model,
        RegisterVersionRequest.builder().content(dmn).source(VersionSource.API).build());
    ModelVersion later = ModelDeployer.register(RuleEngine.client(), store, model, RegisterVersionRequest.builder()
        .content(dmn.replace("<text>15</text>", "<text>30</text>"))
        .source(VersionSource.PORTAL)
        .effectiveDate(OffsetDateTime.now().plusSeconds(3))
        .build());
    assertThat(later.status()).isEqualTo(VersionStatus.APPROVED);
    var input = Map.<String, Object>of("purchaseAmount", 1500, "type", "Gold", "country", "CH");
    assertThat(RuleEngine.evaluate(model, null, input).results()).containsEntry("discount", 15);

    long deadline = System.currentTimeMillis() + 30_000;
    Object discount = 15;
    while (!Integer.valueOf(30).equals(discount) && System.currentTimeMillis() < deadline) {
      sleep(500);
      discount = RuleEngine.evaluate(model, null, input).results().get("discount");
    }
    assertThat(discount).as("active at the effective date, on its own").isEqualTo(30);
    store.delete(model);
  }

  @Test
  void invalidDmn_isRejectedWithReadableMessage() {
    assertThatThrownBy(() -> RuleEngine.register("broken", "<definitions>this is not DMN</definitions>", "me", ""))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_MODEL);
          assertThat(ex.getMessage()).contains("Cannot find the declaration of element 'definitions'");
        });
  }

  @Test
  void dmnEngineIsReady() {
    assertThat(RuleEngine.client().isReady()).isTrue();
  }

  /** @return percent, reason and total of the sample invoice with this tier and this many laptops */
  private static String discount(BpmClient client, String tier, int laptops) {
    Invoice sample = InvoiceSamples.standard();
    sample.setCustomerTier(tier);
    sample.getLines().get(0).setQuantity(laptops);
    Invoice invoice = TestInvoicePricing.price(client, sample);
    return invoice.getDiscountPercent().stripTrailingZeros().toPlainString() + " " + invoice.getDiscountReason() + " "
        + invoice.getTotal().toPlainString();
  }

  private static int silverDiscount(BpmClient client) {
    return silverDiscount(TestInvoicePricing.price(client, InvoiceSamples.standard()));
  }

  private static int silverDiscount(Invoice invoice) {
    assertThat(invoice.getDiscountReason()).isEqualTo("Silver customer");
    return invoice.getDiscountPercent().intValue();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
