package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

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
import com.axonivy.utils.rule.engine.program.internal.RuleErrors;
import com.axonivy.utils.rule.engine.test.TestData;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.bpm.engine.client.BpmClient;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;

/**
 * End to end against the real decision runtime in a container: Ivy process, Business Rule Task, REST,
 * DMN engine and the database of the runtime. The discount of the demo invoice is decided by dmn/invoice-discount.dmn
 * of rule-engine-demo.
 * <p>Build the image first: <code>docker compose build</code>, then <code>mvn verify -Pcontainer.test</code>.</p>
 */
@IvyProcessTest
class RuleEngineContainerTest {

  private static final String API_KEY = "container-secret";
  private static final String DEMO = DiscountTable.MODEL;
  /** The discount of the rule "Silver customer" in dmn/invoice-discount.dmn. */
  private static final String SILVER = "<outputEntry id=\"_discount_rule_6_out_1\"><text>2</text></outputEntry>";

  @SuppressWarnings("resource")
  static GenericContainer<?> runtime = new GenericContainer<>(
      System.getProperty("rule.engine.image", "axonivy/rule-engine-runtime:dev"))
      .withEnv("RULE_ENGINE_API_KEY", API_KEY)
      .withExposedPorts(8080)
      .waitingFor(Wait.forHttp("/q/health/ready").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

  @BeforeAll
  public static void startRuntime() {
    runtime.start();
  }

  @AfterAll
  public static void stopRuntime() {
    runtime.stop();
  }

  @BeforeEach
  public void setup(AppFixture fixture) {
    fixture.var(Var.URL, "http://" + runtime.getHost() + ":" + runtime.getMappedPort(8080));
    fixture.var(Var.API_KEY, API_KEY);
    fixture.var(Var.AUTO_DEPLOY, "true");
    fixture.var(Var.MODEL_PREFIX, "");
    ModelDeployer.reset();
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
    assertThat(version.source()).as("registered from the project on its first use").isEqualTo(VersionSource.PROJECT);
    assertThat(version.author()).contains("rule-engine-demo");
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
    assertThat(saved.error()).as("the runtime refused the model").isNull();
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
    assertThat(saved.error()).as("the runtime refused the model").isNull();
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
    String current = RuleEngine.client().content(DEMO, projectVersion);
    String changed = current.replace(SILVER, SILVER.replace("<text>2</text>", "<text>3</text>"));
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

    // the project is deployed again: its file is registered, but the version of the Portal stays active
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
    var client = RuleEngine.client();
    String model = "effective-" + System.nanoTime();
    String dmn = RuleEngine.client().content("discount", activeVersion(client));
    client.registerVersion(model, RegisterVersionRequest.builder().content(dmn).source(VersionSource.API).build());
    ModelVersion later = client.registerVersion(model, RegisterVersionRequest.builder()
        .content(dmn.replace("<text>15</text>", "<text>30</text>"))
        .source(VersionSource.PORTAL)
        .effectiveDate(OffsetDateTime.now().plusSeconds(3))
        .build());
    assertThat(later.status()).isEqualTo(VersionStatus.APPROVED);
    var input = Map.<String, Object>of("purchaseAmount", 1500, "type", "Gold", "country", "CH");
    assertThat(client.evaluate(model, request(input)).results()).containsEntry("discount", 15);

    long deadline = System.currentTimeMillis() + 30_000;
    Object discount = 15;
    while (!Integer.valueOf(30).equals(discount) && System.currentTimeMillis() < deadline) {
      sleep(500);
      discount = client.evaluate(model, request(input)).results().get("discount");
    }
    assertThat(discount).as("active at the effective date, on its own").isEqualTo(30);
    client.deleteModel(model);
  }

  @Test
  void generatedDmn_isValidAndRunsInTheRuntime(BpmClient client) throws IOException {
    Path generated = Project.current().directory().resolve("dmn").resolve("test-generated.dmn");
    Files.deleteIfExists(generated);
    try {
      TestData data = TestBusinessRuleTask.sampleData();
      data.setDiscount(99);

      TestData result = TestBusinessRuleTask.run(client, "create", data);

      assertThat(result.getErrorMessage()).isNull();
      assertThat(generated).as("generated from the selected fields of the process data").exists();
      assertThat(RuleEngine.client().validate(Files.readString(generated)).messages())
          .as("findings of the DMN validator of the runtime").isEmpty();
      assertThat(result.getDiscount().intValue()).as("the placeholder rule of the generated table").isZero();

      // the rule author fills in a rule, the changed file is live with the next evaluation
      String withRule = Files.readString(generated).replace("<rule id=\"_rule_1\">", """
          <rule id="_rule_gold">
            <inputEntry id="_rule_gold_1"><text>&gt;= 1000</text></inputEntry>
            <inputEntry id="_rule_gold_2"><text>"GOLD"</text></inputEntry>
            <inputEntry id="_rule_gold_3"><text>true</text></inputEntry>
            <inputEntry id="_rule_gold_4"><text>"Zug"</text></inputEntry>
            <inputEntry id="_rule_gold_5"><text>-</text></inputEntry>
            <outputEntry id="_rule_gold_out"><text>12.5</text></outputEntry>
            <annotationEntry><text>VIP Gold customers from Zug</text></annotationEntry>
          </rule>
          <rule id="_rule_1">""");
      Files.writeString(generated, withRule);
      ProjectDmnFiles.refresh();

      TestData decided = TestBusinessRuleTask.run(client, "create", TestBusinessRuleTask.sampleData());
      assertThat(decided.getErrorMessage()).isNull();
      assertThat(decided.getDiscount()).isEqualTo(12.5);
    } finally {
      Files.deleteIfExists(generated); // the folder dmn stays: it holds the models of the tests
      ProjectDmnFiles.refresh();
    }
  }

  @Test
  void invalidDmn_isRejectedWithReadableMessage() {
    assertThatThrownBy(() -> RuleEngine.register("broken", "<definitions>this is not DMN</definitions>", "me", ""))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_MODEL);
          assertThat(ex.getMessage()).contains("not a DMN model");
        });
  }

  @Test
  void wrongApiKey_isAnsweredByTheDemo(BpmClient client, AppFixture fixture) {
    fixture.var(Var.API_KEY, "wrong");

    var result = TestInvoicePricing.execute(client, InvoiceSamples.standard()).subResult();

    assertThat(result.param("errorCode", String.class)).isEqualTo(RuleErrors.DMN);
    assertThat(result.param("errorMessage", String.class)).contains("API key");
  }

  @Test
  void runtimeIsReady() {
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

  private static int activeVersion(com.axonivy.utils.rule.engine.client.RuleEngineClient client) {
    try {
      return client.model("discount").activeVersion();
    } catch (RuleEngineApiException notYetLoaded) {
      return RuleEngine.deploy(Project.current()).stream()
          .filter(version -> version.model().equals("discount"))
          .findFirst().orElseThrow().version();
    }
  }

  private static com.axonivy.utils.rule.engine.api.EvaluateRequest request(Map<String, Object> input) {
    return com.axonivy.utils.rule.engine.api.EvaluateRequest.builder().input(input).build();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
