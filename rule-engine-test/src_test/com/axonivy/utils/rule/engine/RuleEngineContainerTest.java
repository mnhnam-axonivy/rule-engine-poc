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
import com.axonivy.utils.rule.engine.demo.ApprovalDemoData;
import com.axonivy.utils.rule.engine.demo.DiscountDemoData;
import com.axonivy.utils.rule.engine.demo.OrderApprovalData;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.portal.RuleManagement;
import com.axonivy.utils.rule.engine.program.internal.RuleErrors;
import com.axonivy.utils.rule.engine.test.TestData;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.bpm.engine.client.BpmClient;
import ch.ivyteam.ivy.bpm.engine.client.element.BpmElement;
import ch.ivyteam.ivy.bpm.engine.client.element.BpmProcess;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import ch.ivyteam.ivy.scripting.objects.Date;

/**
 * End to end against the real decision runtime in a container: Ivy process, Business Rule Task, REST,
 * DMN engine and the database of the runtime.
 * <p>Build the image first: <code>docker compose build</code>, then <code>mvn verify -Pcontainer.test</code>.</p>
 */
@IvyProcessTest
class RuleEngineContainerTest {

  private static final String API_KEY = "container-secret";
  private static final BpmElement DISCOUNT_DEMO = BpmProcess.name("RuleEngineDemo").elementName("discount");
  private static final BpmElement APPROVAL_DEMO = BpmProcess.name("InvoiceApprovalDemo").elementName("approval");
  private static final BpmElement ORDER_DEMO = BpmProcess.name("OrderApprovalDemo").elementName("order");

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
  void demoProcess_evaluatesDiscount(BpmClient client) {
    var execution = client.start().process(DISCOUNT_DEMO).execute();
    DiscountDemoData data = execution.data().last();

    assertThat(data.getErrorMessage()).isNull();
    assertThat(data.getMember().getDiscount().intValue()).as("Gold member, 1500: rule 1 of dmn/discount.dmn").isEqualTo(15);
    Integer decided = TestBusinessRuleTask.decidedBy(execution, "discount");
    assertThat(decided).as("the version that decided is stored on the case").isNotNull().isPositive();

    var version = RuleEngine.versions("discount").stream().filter(ModelVersion::isActive).findFirst().orElseThrow();
    assertThat(version.version()).isEqualTo(decided);
    assertThat(version.source()).as("registered from the project on its first use").isEqualTo(VersionSource.PROJECT);
    assertThat(version.author()).contains("rule-engine-demo");
  }

  @Test
  void demoProcess_evaluatesWholeModelWithStructureAndDate(BpmClient client) {
    ApprovalDemoData data = client.start().process(APPROVAL_DEMO).execute().data().last();

    assertThat(data.getErrorMessage()).isNull();
    assertThat(data.getRiskLevel()).as("25000 without purchase order").isEqualTo("High");
    assertThat(data.getApproval().getNextStep()).isEqualTo("Route to CFO");
    assertThat(data.getApproval().getFourEyes()).isTrue();
    assertThat(data.getDueDate()).as("invoice date + 30 days").isEqualTo(new Date(2026, 12, 15));
  }

  /**
   * In the Portal a rule owner makes the simple rule of the demo complex, with two inputs
   * the .dmn file of the project does not have. The same order, the same process, no deployment: it goes elsewhere.
   */
  @Test
  @SuppressWarnings("unchecked")
  void ruleMadeComplexInThePortal_routesTheSameOrderElsewhere(BpmClient client) throws IOException {
    OrderApprovalData before = client.start().process(ORDER_DEMO).execute().data().last();
    assertThat(before.getErrorMessage()).isNull();
    assertThat(before.getRoute()).as("8000: everything but a small order goes to a manager").isEqualTo("manager");
    assertThat(before.getApprovedBy()).isEqualTo("manager");

    String complex = Files.readString(Project.current().directory()
        .resolve("src_test/com/axonivy/utils/rule/engine/scenario/order-approval-portal.dmn.xml"));
    var saved = RuleManagement.save("order-approval", complex, "admin", "Large orders of new customers to the director", true);
    assertThat(saved.error()).as("the runtime refused the model").isNull();
    var version = (Map<String, Object>) saved.result();
    assertThat(version).containsEntry("status", "ACTIVE").containsEntry("source", "PORTAL").containsEntry("author", "admin");

    var afterExecution = client.start().process(ORDER_DEMO).execute();
    OrderApprovalData after = afterExecution.data().last();
    assertThat(after.getErrorMessage()).isNull();
    assertThat(after.getRoute()).as("8000, NEW customer: rule 2 of the version from the Portal").isEqualTo("director");
    assertThat(after.getApprovedBy()).isEqualTo("director");
    assertThat(TestBusinessRuleTask.decidedBy(afterExecution, "order-approval")).isEqualTo(version.get("version"));
  }

  @Test
  void versionActivatedFromThePortal_isLiveAtOnce_andSurvivesRedeployment(BpmClient client) {
    var initialExecution = client.start().process(DISCOUNT_DEMO).execute();
    DiscountDemoData initial = initialExecution.data().last();
    assertThat(initial.getMember().getDiscount().intValue()).isEqualTo(15);
    int projectVersion = TestBusinessRuleTask.decidedBy(initialExecution, "discount");

    // a rule owner raises the discount for Gold members in a Rules UI
    String current = RuleEngine.client().content("discount", projectVersion);
    String changed = current.replace("<text>15</text>", "<text>20</text>");
    assertThat(changed).isNotEqualTo(current);
    ModelVersion draft = RuleEngine.register("discount", changed, "rule.editor", "Gold discount raised to 20");
    assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
    assertThat(discount(client)).as("a draft is not live").isEqualTo(15);

    assertThat(RuleEngine.approve("discount", draft.version()).status()).isEqualTo(VersionStatus.APPROVED);
    ModelVersion active = RuleEngine.activate("discount", draft.version(), "rule.approver");
    assertThat(active.status()).isEqualTo(VersionStatus.ACTIVE);
    assertThat(active.activatedBy()).isEqualTo("rule.approver");

    var activationExecution = client.start().process(DISCOUNT_DEMO).execute();
    DiscountDemoData afterActivation = activationExecution.data().last();
    assertThat(afterActivation.getMember().getDiscount().intValue()).as("live without restart or deployment").isEqualTo(20);
    assertThat(TestBusinessRuleTask.decidedBy(activationExecution, "discount")).isEqualTo(draft.version());

    // the project is deployed again: its file is registered, but the version of the Portal stays active
    ModelDeployer.reset();
    var deployed = RuleEngine.deploy(Project.current());
    assertThat(deployed).extracting(ModelVersion::model).contains("discount", "invoice-approval");
    assertThat(discount(client)).as("redeployment does not overwrite the version of the Portal").isEqualTo(20);

    // rollback: the previous version is active again
    ModelVersion rolledBack = RuleEngine.rollback("discount", "rule.approver");
    assertThat(rolledBack.version()).isEqualTo(projectVersion);
    assertThat(discount(client)).isEqualTo(15);
    assertThat(RuleEngine.versions("discount")).filteredOn(ModelVersion::isActive).hasSize(1);
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
      Files.deleteIfExists(generated);
      Files.deleteIfExists(generated.getParent());
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
  void wrongApiKey_raisesBpmnError(BpmClient client, AppFixture fixture) {
    fixture.var(Var.API_KEY, "wrong");

    DiscountDemoData data = client.start().process(DISCOUNT_DEMO).execute().data().last();

    assertThat(data.getErrorCode()).isEqualTo(RuleErrors.DMN);
    assertThat(data.getErrorMessage()).contains("API key");
  }

  @Test
  void runtimeIsReady() {
    assertThat(RuleEngine.client().isReady()).isTrue();
  }

  private static int discount(BpmClient client) {
    DiscountDemoData data = client.start().process(DISCOUNT_DEMO).execute().data().last();
    assertThat(data.getErrorMessage()).isNull();
    return data.getMember().getDiscount().intValue();
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
