package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.program.internal.RuleErrors;
import com.axonivy.utils.rule.engine.program.internal.RuleTaskExecutor;
import com.axonivy.utils.rule.engine.test.Address;
import com.axonivy.utils.rule.engine.test.Customer;
import com.axonivy.utils.rule.engine.test.Segment;
import com.axonivy.utils.rule.engine.test.TestData;
import com.axonivy.utils.rule.engine.test.mock.MockDmnEngine;
import com.axonivy.utils.rule.engine.test.mock.TestStore;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.bpm.engine.client.BpmClient;
import ch.ivyteam.ivy.bpm.engine.client.ExecutionResult;
import ch.ivyteam.ivy.bpm.engine.client.element.BpmProcess;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import ch.ivyteam.ivy.scripting.objects.Date;
import ch.ivyteam.ivy.scripting.objects.DateTime;
import ch.ivyteam.ivy.scripting.objects.Duration;
import ch.ivyteam.ivy.scripting.objects.Time;
import tools.jackson.databind.JsonNode;

/**
 * The Business Rule Task in a process. The versions are stored in the file area of the test application, the DMN
 * engine is replaced by {@link MockDmnEngine}.
 */
@IvyProcessTest(enableWebServer = true)
class TestBusinessRuleTask {

  private static final BpmProcess RULE_TEST = BpmProcess.name("RuleTest");

  @BeforeEach
  public void setup(AppFixture fixture) {
    MockDmnEngine.reset();
    TestStore.clear();
    fixture.var(Var.URL, MockDmnEngine.url());
    fixture.var(Var.AUTO_DEPLOY, "true");
    fixture.var(Var.TIMEOUT_SECONDS, "10");
  }

  /** The element maps the inputs to fields of the process data and of nested data classes, the result to in.discount. */
  @Test
  void inputsAndResults_goToTheMappedFields(BpmClient client) {
    TestStore.store("mock-discount", "discount", "amount", "segment", "city", "vip");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":12.5}"));

    var execution = execute(client, "mockDiscount", sampleData());
    TestData result = execution.subResult().param("data", TestData.class);

    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getDiscount()).as("result 'discount' is written to in.discount").isEqualTo(12.5);
    assertThat(decidedBy(execution, "mock-discount")).as("the model version is stored on the case").isEqualTo(1);

    var call = MockDmnEngine.calls("evaluate").get(0);
    assertThat(call.dmn()).as("the file of the active version")
        .isEqualTo(VersionStore.current().content("mock-discount", 1));
    JsonNode input = call.input();
    assertThat(input.get("amount").asInt()).as("in.amount").isEqualTo(1500);
    assertThat(input.get("segment").asString()).as("in.customer.segment").isEqualTo("GOLD");
    assertThat(input.get("city").asString()).as("in.customer.address.city").isEqualTo("Zug");
    assertThat(input.get("vip").asBoolean()).as("in.customer.vip").isTrue();
  }

  @Test
  void fieldsThatAreNotSet_areSentAsNull(BpmClient client) {
    TestStore.store("mock-discount", "discount", "amount", "segment", "city");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":null}"));
    var data = new TestData();
    data.setDiscount(99);

    TestData result = run(client, "mockDiscount", data);

    assertThat(result.getErrorMessage()).isNull();
    JsonNode input = MockDmnEngine.calls("evaluate").get(0).input();
    assertThat(input.propertyNames()).containsExactlyInAnyOrder("amount", "segment", "city");
    assertThat(input.get("amount").isNull()).isTrue();
    assertThat(input.get("segment").isNull()).isTrue();
    assertThat(result.getDiscount()).as("a null result clears the field").isNull();
  }

  /** The mapping of the element: an input reads a field or a calculation, the result fills a field of another name. */
  @Test
  void mappedInputsAndResult_useTheChosenProcessData(BpmClient client) {
    // the element maps amount to in.purchaseAmount, name to a calculation and score to in.points
    TestStore.store("mock-explicit", "score", "amount", "name");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"score\":42}"));
    var data = sampleData();
    data.setPurchaseAmount(2500);

    TestData result = run(client, "mapped", data);

    assertThat(result.getErrorMessage()).isNull();
    JsonNode input = MockDmnEngine.calls("evaluate").get(0).input();
    assertThat(input.get("amount").asInt()).as("chosen: in.purchaseAmount, not in.amount").isEqualTo(2500);
    assertThat(input.get("name").asString()).as("chosen: a calculation").isEqualTo("Jane Doe (GOLD)");
    assertThat(result.getPoints()).as("the result 'score' fills the chosen in.points").isEqualTo(42);
  }

  @Test
  void mappedEntryOfAStructuredResult_fillsTheChosenField(BpmClient client) {
    // the element maps the entry product of the decision offer to in.nextStep; score is left empty
    TestStore.store("mock-recommend", "offer", "amount");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"offer\":{\"product\":\"Lamp\",\"score\":0.5}}"));

    TestData result = run(client, "mappedStructure", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getNextStep()).as("chosen: in.nextStep").isEqualTo("Lamp");
    assertThat(result.getRecommendation()).as("score is left empty: not written, though in.recommendation.score has its name")
        .isNull();
  }

  @Test
  void wholeModel_everyResultGoesToItsField(BpmClient client) {
    TestStore.store("mock-all", "any", "amount");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("""
        {"approved":true,"nextStep":"Route to CFO","dueDate":"2026-12-15","deliveredAt":"2026-12-01T10:30:00",
         "upgrade":"SILVER","recommendation":{"product":"Sofa","score":0.75,"validUntil":"2027-01-31"},
         "labels":["urgent","b2b"],"points":3,"discount":10,"type":"Gold"}
        """));

    TestData result = run(client, "wholeModel", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getApproved()).isTrue();
    assertThat(result.getNextStep()).isEqualTo("Route to CFO");
    assertThat(result.getDueDate()).as("date").isEqualTo(new Date(2026, 12, 15));
    assertThat(result.getDeliveredAt()).as("date and time").isEqualTo(new DateTime(2026, 12, 1, 10, 30, 0));
    assertThat(result.getUpgrade()).as("enum").isEqualTo(Segment.SILVER);
    assertThat(result.getRecommendation().getProduct()).as("nested data class").isEqualTo("Sofa");
    assertThat(result.getRecommendation().getScore()).isEqualTo(0.75);
    assertThat(result.getRecommendation().getValidUntil()).isEqualTo(new Date(2027, 1, 31));
    assertThat(result.getLabels()).as("list").containsExactly("urgent", "b2b");
    assertThat(result.getPoints()).as("number as Integer").isEqualTo(3);
    assertThat(result.getDiscount().intValue()).as("number").isEqualTo(10);
    assertThat(result.getType()).as("no field chosen: not written, though in.type has its name").isEqualTo("Silver");
  }

  /** The element maps the entries of the structure to the fields of a nested data class. */
  @Test
  void structuredResult_fillsTheFieldsOfADataClass(BpmClient client) {
    TestStore.store("mock-recommend", "offer", "amount");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"offer\":{\"product\":\"Lamp\",\"score\":0.5}}"));

    TestData result = run(client, "structuredResult", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getRecommendation().getProduct()).isEqualTo("Lamp");
    assertThat(result.getRecommendation().getScore()).isEqualTo(0.5);
  }

  @Test
  void allSupportedTypes_areSentAsJson(BpmClient client) {
    TestStore.store("mock-types", "check", "customer", "amount", "quantity", "orderDate", "orderedAt",
        "openingTime", "deliveryTime", "positions");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"check\":true}"));
    var data = sampleData();
    data.setAmount(99.95);
    data.setQuantity(3);
    data.setOrderDate(new Date(2026, 11, 15));
    data.setOrderedAt(new DateTime(2026, 11, 15, 10, 30, 0));
    data.setOpeningTime(new Time(8, 0, 0));
    data.setDeliveryTime(new Duration(36 * 3600L));
    var position = new Address();
    position.setCity("Luzern");
    position.setCountry("CH");
    data.setPositions(ch.ivyteam.ivy.scripting.objects.List.create(Address.class, List.of(position)));

    TestData result = run(client, "types", data);

    assertThat(result.getErrorMessage()).isNull();
    JsonNode input = MockDmnEngine.calls("evaluate").get(0).input();
    assertThat(input.get("amount").asDouble()).as("number").isEqualTo(99.95);
    assertThat(input.get("quantity").asInt()).isEqualTo(3);
    assertThat(input.get("orderDate").asString()).as("date").isEqualTo("2026-11-15");
    assertThat(input.get("orderedAt").asString()).as("date and time").isEqualTo("2026-11-15T10:30:00");
    assertThat(input.get("openingTime").asString()).as("time").isEqualTo("08:00:00");
    assertThat(input.get("deliveryTime").asString()).as("duration").isEqualTo("PT36H");
    assertThat(input.get("positions").get(0).get("city").asString()).as("list of data classes").isEqualTo("Luzern");
    JsonNode customer = input.get("customer");
    assertThat(customer.get("name").asString()).as("nested data class").isEqualTo("Jane Doe");
    assertThat(customer.get("segment").asString()).as("enum").isEqualTo("GOLD");
    assertThat(customer.get("age").asInt()).isEqualTo(41);
    assertThat(customer.get("vip").asBoolean()).as("boolean").isTrue();
    assertThat(customer.get("birthday").asString()).isEqualTo("1985-03-07");
    assertThat(customer.get("lastContact").asString()).isEqualTo("2026-11-14T08:15:30");
    assertThat(customer.get("tags").get(1).asString()).as("list").isEqualTo("newsletter");
    assertThat(customer.get("address").get("country").asString()).isEqualTo("CH");
  }

  @Test
  void dmnError_raisesBpmnError(BpmClient client) {
    TestStore.store("mock-discount", "discount", "amount");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.failed("discount", "the value is not allowed"));

    TestData result = run(client, "mockDiscount", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.DMN);
    assertThat(result.getErrorMessage()).contains("the value is not allowed");
    assertThat(result.getDiscount()).isNull();
  }

  @Test
  void unknownModel_raisesBpmnError(BpmClient client) {
    TestData result = run(client, "mockDiscount", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.DMN);
    assertThat(result.getErrorMessage()).contains("mock-discount");
    assertThat(MockDmnEngine.calls()).as("nothing to evaluate").isEmpty();
  }

  @Test
  void dmnEngineNotReachable_raisesBpmnError(BpmClient client, AppFixture fixture) {
    TestStore.store("mock-discount", "discount", "amount");
    fixture.var(Var.URL, "http://localhost:9");
    fixture.var(Var.CONNECT_TIMEOUT_SECONDS, "2");

    TestData result = run(client, "mockDiscount", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.UNREACHABLE);
    assertThat(result.getErrorMessage()).contains("http://localhost:9").contains(Var.URL);
  }

  @Test
  void timeout_raisesBpmnError(BpmClient client, AppFixture fixture) {
    fixture.var(Var.TIMEOUT_SECONDS, "1");
    TestStore.store("mock-slow", "slow");
    MockDmnEngine.onEvaluate(call -> {
      sleep(4000);
      return MockDmnEngine.results("{\"slow\":1}");
    });

    long start = System.currentTimeMillis();
    TestData result = run(client, "timeout", sampleData());
    long duration = System.currentTimeMillis() - start;

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.TIMEOUT);
    assertThat(duration).as("RuleEngine.TimeoutSeconds is 1 second").isLessThan(3500);
  }

  /** The element maps no process data to the input type: the field in.type is not taken for its name. */
  @Test
  void inputWithoutMapping_raisesMappingError(BpmClient client) {
    TestStore.store("mock-discount", "discount", "amount", "type");

    TestData result = run(client, "mockDiscount", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.MAPPING);
    assertThat(result.getErrorMessage()).contains("[type]")
        .contains("Choose the process data of each input in the section Input of the Business Rule Task");
    assertThat(MockDmnEngine.calls("evaluate")).isEmpty();
  }

  @Test
  void resultThatDoesNotFitTheField_raisesMappingError(BpmClient client) {
    TestStore.store("mock-discount", "discount", "amount");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":\"a lot\"}"));

    TestData result = run(client, "mockDiscount", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.MAPPING);
    assertThat(result.getErrorMessage()).contains("in.discount").contains("a lot");
  }

  @Test
  void elementWithoutDecision_raisesMappingError(BpmClient client) {
    TestData result = run(client, "unconfigured", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.MAPPING);
    assertThat(result.getErrorMessage()).contains("No decision selected");
  }

  @Test
  void dmnFileOfTheProject_isStoredAsVersion_once(BpmClient client) {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":15}"));
    var data = sampleData();
    data.setType("Gold");

    TestData first = run(client, "demoDiscount", data);
    TestData second = run(client, "demoDiscount", data);

    assertThat(first.getErrorMessage()).isNull();
    assertThat(first.getDiscount().intValue()).isEqualTo(15);
    assertThat(second.getDiscount().intValue()).isEqualTo(15);
    var versions = VersionStore.current().versions("discount");
    assertThat(versions).as("unchanged content is stored once").hasSize(1);
    assertThat(versions.get(0).source()).isEqualTo(VersionSource.PROJECT);
    assertThat(versions.get(0).author()).as("the application and its version").matches(".+ v\\d+");
    assertThat(versions.get(0).description()).startsWith("Deployed with project rule-engine-test");
    assertThat(versions.get(0).status()).as("the first version goes live").isEqualTo(VersionStatus.ACTIVE);
    assertThat(MockDmnEngine.calls("validate")).as("the DMN engine checks the file before it is stored").hasSize(1);
    var evaluations = MockDmnEngine.calls("evaluate");
    assertThat(evaluations).as("every evaluation sends the file").hasSize(2);
    assertThat(evaluations.get(0).dmn()).isEqualTo(VersionStore.current().content("discount", 1));
    JsonNode input = evaluations.get(0).input();
    assertThat(input.propertyNames()).as("the inputs of dmn/discount.dmn")
        .containsExactlyInAnyOrder("purchaseAmount", "type", "country");
    assertThat(input.get("type").asString()).isEqualTo("Gold");
  }

  @Test
  void dmnFileWithErrors_isNotStored(BpmClient client) {
    MockDmnEngine.onValidate(call -> MockDmnEngine.findings("no such column"));

    TestData result = run(client, "demoDiscount", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.DMN);
    assertThat(result.getErrorMessage()).contains("no such column");
    assertThat(VersionStore.current().exists("discount")).isFalse();
  }

  /**
   * A rule owner added a column in the Portal: the file does not know it, the version that answers needs it. The
   * element maps it already.
   */
  @Test
  void versionFromThePortal_answers_withTheInputsItNeeds(BpmClient client) {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":20}"));
    run(client, "demoDiscount", sampleData()); // stores the file as version 1
    TestStore.store("discount", TestStore.dmn("discount", "discount", "purchaseAmount", "type", "country", "segment"),
        VersionSource.PORTAL, true);

    var execution = execute(client, "demoDiscount", sampleData());
    TestData result = execution.subResult().param("data", TestData.class);

    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getDiscount().intValue()).isEqualTo(20);
    assertThat(decidedBy(execution, "discount")).isEqualTo(2);
    var evaluation = MockDmnEngine.calls("evaluate").get(1);
    assertThat(evaluation.dmn()).isEqualTo(VersionStore.current().content("discount", 2));
    assertThat(evaluation.input().propertyNames()).as("the inputs of the version from the Portal")
        .containsExactlyInAnyOrder("purchaseAmount", "type", "country", "segment");
    assertThat(evaluation.input().get("segment").asString()).as("mapped to in.customer.segment").isEqualTo("GOLD");
    assertThat(VersionStore.current().versions("discount")).as("the unchanged file is not stored again").hasSize(2);
  }

  /** A column of the Portal that the element does not map stops the evaluation, until the process maps it. */
  @Test
  void versionFromThePortal_withAnInputTheElementDoesNotMap_raisesMappingError(BpmClient client) {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":20}"));
    run(client, "demoDiscount", sampleData()); // stores the file as version 1
    TestStore.store("discount", TestStore.dmn("discount", "discount", "purchaseAmount", "type", "country", "vip"),
        VersionSource.PORTAL, true);

    TestData result = run(client, "demoDiscount", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.MAPPING);
    assertThat(result.getErrorMessage()).contains("[vip]");
    assertThat(MockDmnEngine.calls("evaluate")).as("only the evaluation of version 1").hasSize(1);
  }

  /** A version from the project is replaced by the file: the file says what is needed. */
  @Test
  void versionFromTheProject_isReplacedByTheFile(BpmClient client) {
    TestStore.store("discount", TestStore.dmn("discount", "discount", "purchaseAmount", "type", "country", "segment"),
        VersionSource.PROJECT, null);
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":15}"));

    run(client, "demoDiscount", sampleData());

    var evaluation = MockDmnEngine.calls("evaluate").get(0);
    assertThat(evaluation.dmn()).as("the file, stored as version 2")
        .isEqualTo(VersionStore.current().content("discount", 2));
    assertThat(evaluation.input().propertyNames()).containsExactlyInAnyOrder("purchaseAmount", "type", "country");
    assertThat(VersionStore.current().version("discount", 1).status()).isEqualTo(VersionStatus.RETIRED);
  }

  @Test
  void autoDeployOff_evaluatesTheStoredVersion(BpmClient client, AppFixture fixture) {
    fixture.var(Var.AUTO_DEPLOY, "false");
    TestStore.store("discount", "discount", "purchaseAmount");
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":15}"));

    TestData result = run(client, "demoDiscount", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    assertThat(MockDmnEngine.calls("validate")).as("the file of the project is not stored").isEmpty();
    assertThat(MockDmnEngine.calls("evaluate").get(0).input().propertyNames()).containsExactly("purchaseAmount");
  }

  static TestData run(BpmClient client, String signature, TestData data) {
    return execute(client, signature, data).subResult().param("data", TestData.class);
  }

  static ExecutionResult execute(BpmClient client, String signature, TestData data) {
    return client.start()
        .subProcess(RULE_TEST.elementName(signature + "(TestData)"))
        .withParam("data", data)
        .execute();
  }

  /** @return the version that decided, as the Business Rule Task stores it on the case */
  static Integer decidedBy(ExecutionResult execution, String model) {
    String version = execution.workflow().activeCase().customFields()
        .stringField(RuleTaskExecutor.VERSION_FIELD_PREFIX + model).getOrNull();
    return version == null ? null : Integer.valueOf(version);
  }

  static TestData sampleData() {
    var address = new Address();
    address.setCity("Zug");
    address.setCountry("CH");
    var customer = new Customer();
    customer.setName("Jane Doe");
    customer.setSegment(Segment.GOLD);
    customer.setAge(41);
    customer.setVip(true);
    customer.setBirthday(new Date(1985, 3, 7));
    customer.setLastContact(new DateTime(2026, 11, 14, 8, 15, 30));
    customer.setTags(ch.ivyteam.ivy.scripting.objects.List.create(String.class, List.of("vip", "newsletter")));
    customer.setAddress(address);
    var data = new TestData();
    data.setCustomer(customer);
    data.setAmount(1500);
    data.setPurchaseAmount(1500);
    data.setType("Silver");
    return data;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
