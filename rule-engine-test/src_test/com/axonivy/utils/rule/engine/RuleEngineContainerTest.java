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
import com.axonivy.utils.rule.engine.api.ModelInfo;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.demo.InvoiceSamples;
import com.axonivy.utils.rule.engine.demo.PricedLine;
import com.axonivy.utils.rule.engine.demo.PricingResult;
import com.axonivy.utils.rule.engine.demo.SalesInvoice;
import com.axonivy.utils.rule.engine.demo.TaxFormula;
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
 * DMN engine and the database of the runtime. The demo invoice is priced by dmn/invoice-pricing.dmn of rule-engine-demo.
 * <p>Build the image first: <code>docker compose build</code>, then <code>mvn verify -Pcontainer.test</code>.</p>
 */
@IvyProcessTest
class RuleEngineContainerTest {

  private static final String API_KEY = "container-secret";
  private static final String PRICING = TaxFormula.MODEL;
  /** The line discount of the rule "Hardware: 10 units or more" in dmn/invoice-pricing.dmn. */
  private static final String HARDWARE_10 = "<outputEntry id=\"_lineDiscount_rule_6_out_1\"><text>6</text></outputEntry>";

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
  void demoInvoice_isPricedByTheWholeModel(BpmClient client) {
    var execution = TestInvoicePricing.execute(client, sample());
    PricingResult pricing = execution.subResult().param("pricing", PricingResult.class);

    assertThat(pricing.getErrorMessage()).isNull();
    assertThat(pricing.getPricedLines()).extracting(PricedLine::getDiscountReason).as("one rule for each line")
        .containsExactly("Hardware: 10 units or more", "Volume licence: 20 seats or more", "Services are not discounted",
            "Consumables: 50 or more", "Books: 10 copies or more");
    assertThat(pricing.getPricedLines()).extracting(line -> line.getVat().toPlainString())
        .containsExactly("913.68", "206.55", "97.20", "111.78", "13.34");
    assertThat(pricing.getPricedLines().get(4).getVatRate()).as("reduced rate for books in CH").isEqualByComparingTo("2.6");
    assertThat(pricing.getOrderDiscounts()).isEmpty();
    assertThat(pricing.getShipping().getFee()).isEqualByComparingTo("0");
    assertThat(pricing.getTotals().getNet()).isEqualByComparingTo("16923.00");
    assertThat(pricing.getTotals().getVat()).isEqualByComparingTo("1342.55");
    assertThat(pricing.getTotals().getTotal()).isEqualByComparingTo("18265.55");
    assertThat(pricing.getApproval().getRoute()).isEqualTo("Sales manager");
    assertThat(pricing.getApproval().getReasons()).containsExactly("A line discount of 15% or more");

    Integer decided = TestBusinessRuleTask.decidedBy(execution, PRICING);
    assertThat(decided).as("the version that decided is stored on the case").isNotNull().isPositive();
    var version = RuleEngine.versions(PRICING).stream().filter(ModelVersion::isActive).findFirst().orElseThrow();
    assertThat(version.version()).isEqualTo(decided);
    assertThat(version.source()).as("registered from the project on its first use").isEqualTo(VersionSource.PROJECT);
    assertThat(version.author()).contains("rule-engine-demo");
  }

  @Test
  void demoInvoice_otherConditions_otherRules(BpmClient client) {
    SalesInvoice germany = sample();
    germany.setCountry("DE");
    PricingResult inGermany = TestInvoicePricing.price(client, germany);
    assertThat(inGermany.getPricedLines().get(4).getDiscountReason()).isEqualTo("Fixed book price in DE and AT: no discount");
    assertThat(inGermany.getPricedLines()).extracting(line -> line.getVatRate().toPlainString())
        .containsExactly("19", "19", "19", "19", "7");
    assertThat(inGermany.getTotals().getTotal()).isEqualByComparingTo("20105.70");
    assertThat(inGermany.getApproval().getRoute()).isEqualTo("Finance director");
    assertThat(inGermany.getApproval().getReasons())
        .containsExactly("A line discount of 15% or more", "Invoice total above 20,000");

    SalesInvoice stacked = sample();
    stacked.setCustomerTier("Platinum");
    stacked.setPaymentTerms("Prepaid");
    stacked.setPromoCode("winter26");
    PricingResult discounts = TestInvoicePricing.price(client, stacked);
    assertThat(discounts.getOrderDiscounts()).as("COLLECT: every rule that matches")
        .extracting(discount -> discount.getPercent().intValue()).containsExactly(4, 2, 5);
    assertThat(discounts.getOrderDiscountPercent()).as("11 percent, capped").isEqualByComparingTo("10");
    assertThat(discounts.getPricedLines().get(0).getDiscountReason())
        .isEqualTo("Hardware: 10 units or more, Gold and Platinum");
    assertThat(discounts.getTotals().getTotal()).isEqualByComparingTo("15972.00");

    SalesInvoice expired = sample();
    expired.setPromoCode("WINTER26");
    expired.setInvoiceDate(java.time.LocalDate.of(2027, 3, 1));
    assertThat(TestInvoicePricing.price(client, expired).getOrderDiscounts()).singleElement()
        .satisfies(discount -> assertThat(discount.getReason()).contains("only valid from 1 Nov 2026 to 31 Jan 2027"));
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
   * The Portal story of the tutorial: a rule owner adds the input customer and a key account rule to the table
   * orderDiscounts. The invoice has a field customer, which the file of the project does not read. The same invoice,
   * the same process, no deployment: it costs less. The walkthrough of the tutorial plays the same version.
   */
  @Test
  @SuppressWarnings("unchecked")
  void keyAccountAddedInThePortal_pricesTheSameInvoiceLower(BpmClient client) throws IOException {
    assertThat(TestInvoicePricing.price(client, sample()).getOrderDiscounts()).isEmpty();

    String keyAccount = Files.readString(Project.current().directory()
        .resolve("src_test/com/axonivy/utils/rule/engine/scenario/invoice-pricing-key-account.dmn.xml"));
    var saved = RuleManagement.save(PRICING, keyAccount, "admin", "Key account discount for Bluebird Architects", true);
    assertThat(saved.error()).as("the runtime refused the model").isNull();
    var version = (Map<String, Object>) saved.result();
    try {
      var execution = TestInvoicePricing.execute(client, sample());
      PricingResult pricing = execution.subResult().param("pricing", PricingResult.class);
      assertThat(pricing.getErrorMessage()).isNull();
      assertThat(pricing.getOrderDiscounts()).singleElement().satisfies(discount -> {
        assertThat(discount.getReason()).isEqualTo("Key account");
        assertThat(discount.getPercent()).isEqualByComparingTo("3");
      });
      assertThat(pricing.getPricedLines()).extracting(line -> line.getOrderDiscount().stripTrailingZeros().toPlainString())
          .as("each line its share").containsExactly("338.4", "76.5", "36", "41.4", "15.39");
      assertThat(pricing.getTotals().getTotal()).isEqualByComparingTo("17717.58");
      assertThat(TestBusinessRuleTask.decidedBy(execution, PRICING)).isEqualTo(version.get("version"));
    } finally {
      RuleEngine.rollback(PRICING, "admin");
    }
    assertThat(TestInvoicePricing.price(client, sample()).getTotals().getTotal()).as("rolled back").isEqualByComparingTo("18265.55");
  }

  /** The story of the demo: a rule owner raises a discount, the same invoice is priced otherwise, and back. */
  @Test
  void versionActivatedFromThePortal_isLiveAtOnce_andSurvivesRedeployment(BpmClient client) {
    var initialExecution = TestInvoicePricing.execute(client, sample());
    assertThat(hardwareDiscount(initialExecution.subResult().param("pricing", PricingResult.class))).isEqualTo(6);
    int projectVersion = TestBusinessRuleTask.decidedBy(initialExecution, PRICING);

    // a rule owner raises the discount for 10 laptops or more
    String current = RuleEngine.client().content(PRICING, projectVersion);
    String changed = current.replace(HARDWARE_10, HARDWARE_10.replace("<text>6</text>", "<text>8</text>"));
    assertThat(changed).isNotEqualTo(current);
    ModelVersion draft = RuleEngine.register(PRICING, changed, "rule.editor", "Hardware 10+ raised to 8%");
    assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
    assertThat(hardwareDiscount(client)).as("a draft is not live").isEqualTo(6);

    assertThat(RuleEngine.approve(PRICING, draft.version()).status()).isEqualTo(VersionStatus.APPROVED);
    ModelVersion active = RuleEngine.activate(PRICING, draft.version(), "rule.approver");
    assertThat(active.status()).isEqualTo(VersionStatus.ACTIVE);
    assertThat(active.activatedBy()).isEqualTo("rule.approver");

    var activationExecution = TestInvoicePricing.execute(client, sample());
    PricingResult afterActivation = activationExecution.subResult().param("pricing", PricingResult.class);
    assertThat(hardwareDiscount(afterActivation)).as("live without restart or deployment").isEqualTo(8);
    assertThat(afterActivation.getPricedLines().get(0).getNet()).isEqualByComparingTo("11040.00");
    assertThat(TestBusinessRuleTask.decidedBy(activationExecution, PRICING)).isEqualTo(draft.version());

    // the project is deployed again: its file is registered, but the version of the Portal stays active
    ModelDeployer.reset();
    var deployed = RuleEngine.deploy(Project.current());
    assertThat(deployed).extracting(ModelVersion::model).contains(PRICING, "discount");
    assertThat(hardwareDiscount(client)).as("redeployment does not overwrite the version of the Portal").isEqualTo(8);

    // rollback: the previous version is active again
    ModelVersion rolledBack = RuleEngine.rollback(PRICING, "rule.approver");
    assertThat(rolledBack.version()).isEqualTo(projectVersion);
    assertThat(hardwareDiscount(client)).isEqualTo(6);
    assertThat(RuleEngine.versions(PRICING)).filteredOn(ModelVersion::isActive).hasSize(1);
  }

  /** The tax formula of the demo page: tried on a copy of the model, the live rules stay as they are. */
  @Test
  void taxFormula_triedOnACopy_leavesTheLiveRulesAlone(BpmClient client) {
    var execution = TestInvoicePricing.execute(client, sample());
    int version = TestBusinessRuleTask.decidedBy(execution, PRICING);
    int versions = RuleEngine.versions(PRICING).size();
    assertThat(TaxFormula.of(version)).isEqualTo(TaxFormula.DEFAULT);

    var swiss = TaxFormula.tryFormula(sample(), version, "round half up(net * rate / 100 * 20, 0) / 20");

    assertThat(swiss.getErrorMessage()).isNull();
    assertThat(swiss.getPricedLines()).extracting(line -> line.getVat().stripTrailingZeros().toPlainString())
        .as("rounded to 5 centimes").containsExactly("913.7", "206.55", "97.2", "111.8", "13.35");
    assertThat(swiss.getTotals().getVat()).isEqualByComparingTo("1342.60");

    var broken = TaxFormula.tryFormula(sample(), version, "net * rate /");
    assertThat(broken.getErrorMessage()).contains("syntax error").doesNotContain("DMN id");
    var unknown = TaxFormula.tryFormula(sample(), version, "netto * rate");
    assertThat(unknown.getErrorMessage()).contains("Unknown variable 'netto'");

    assertThat(RuleEngine.versions(PRICING)).as("no version was added to the live model").hasSize(versions);
    assertThat(RuleEngine.client().models()).extracting(ModelInfo::name).as("every copy is deleted")
        .noneMatch(name -> name.contains("-try-"));
    assertThat(hardwareDiscount(client)).isEqualTo(6);
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

    PricingResult pricing = TestInvoicePricing.price(client, sample());

    assertThat(pricing.getErrorCode()).isEqualTo(RuleErrors.DMN);
    assertThat(pricing.getErrorMessage()).contains("API key");
  }

  @Test
  void runtimeIsReady() {
    assertThat(RuleEngine.client().isReady()).isTrue();
  }

  private static SalesInvoice sample() {
    return InvoiceSamples.standard(TestInvoicePricing.INVOICE_DATE);
  }

  private static int hardwareDiscount(BpmClient client) {
    return hardwareDiscount(TestInvoicePricing.price(client, sample()));
  }

  private static int hardwareDiscount(PricingResult pricing) {
    assertThat(pricing.getErrorMessage()).isNull();
    return pricing.getPricedLines().get(0).getDiscountPercent().intValue();
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
