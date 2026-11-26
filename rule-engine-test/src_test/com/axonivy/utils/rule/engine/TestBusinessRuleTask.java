package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.program.internal.RuleErrors;
import com.axonivy.utils.rule.engine.program.internal.RuleTaskExecutor;
import com.axonivy.utils.rule.engine.test.Address;
import com.axonivy.utils.rule.engine.test.Customer;
import com.axonivy.utils.rule.engine.test.Segment;
import com.axonivy.utils.rule.engine.test.TestData;
import com.axonivy.utils.rule.engine.test.mock.MockRuntime;

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

@IvyProcessTest(enableWebServer = true)
class TestBusinessRuleTask {

  private static final BpmProcess RULE_TEST = BpmProcess.name("RuleTest");

  @BeforeEach
  public void setup(AppFixture fixture) {
    MockRuntime.reset();
    ModelDeployer.reset();
    fixture.var(Var.URL, MockRuntime.url());
    fixture.var(Var.API_KEY, "test-key");
    fixture.var(Var.AUTO_DEPLOY, "true");
    fixture.var(Var.TIMEOUT_SECONDS, "10");
    fixture.var(Var.MODEL_PREFIX, "");
  }

  @Test
  void inputsAndResultsAreMappedByName(BpmClient client) {
    MockRuntime.model("mock-discount", "discount", "amount", "segment", "city", "vip");
    MockRuntime.onEvaluate(call -> MockRuntime.results(7, "{\"discount\":12.5}"));

    var execution = execute(client, "byName", sampleData());
    TestData result = execution.subResult().param("data", TestData.class);

    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getDiscount()).as("result 'discount' is written to in.discount").isEqualTo(12.5);
    assertThat(decidedBy(execution, "mock-discount")).as("the model version is stored on the case").isEqualTo(7);

    var call = MockRuntime.calls("evaluate").get(0);
    assertThat(call.model()).isEqualTo("mock-discount");
    assertThat(call.apiKey()).isEqualTo("test-key");
    assertThat(call.body().get("decision").asString()).isEqualTo("discount");
    JsonNode input = call.body().get("input");
    assertThat(input.get("amount").asInt()).as("in.amount").isEqualTo(1500);
    assertThat(input.get("segment").asString()).as("in.customer.segment").isEqualTo("GOLD");
    assertThat(input.get("city").asString()).as("in.customer.address.city").isEqualTo("Zug");
    assertThat(input.get("vip").asBoolean()).as("in.customer.vip").isTrue();
  }

  @Test
  void fieldsThatAreNotSet_areSentAsNull(BpmClient client) {
    MockRuntime.model("mock-discount", "discount", "amount", "segment", "city");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"discount\":null}"));
    var data = new TestData();
    data.setDiscount(99);

    TestData result = run(client, "byName", data);

    assertThat(result.getErrorMessage()).isNull();
    JsonNode input = MockRuntime.calls("evaluate").get(0).body().get("input");
    assertThat(input.propertyNames()).containsExactlyInAnyOrder("amount", "segment", "city");
    assertThat(input.get("amount").isNull()).isTrue();
    assertThat(input.get("segment").isNull()).isTrue();
    assertThat(result.getDiscount()).as("a null result clears the field").isNull();
  }

  @Test
  void inputAndOutputFields_areNotUsedForAnExistingDecision(BpmClient client) {
    // the element says "total = in.amount, in.customer.name, in.customer.address.city" and "in.points"
    MockRuntime.model("mock-explicit", "score", "amount", "name", "city");
    MockRuntime.onEvaluate(call -> MockRuntime.results(2, "{\"score\":42}"));

    TestData result = run(client, "fieldsOfAnExistingDecision", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    JsonNode input = MockRuntime.calls("evaluate").get(0).body().get("input");
    assertThat(input.propertyNames()).as("the inputs of the decision, found by name")
        .containsExactlyInAnyOrder("amount", "name", "city");
    assertThat(input.get("amount").asInt()).isEqualTo(1500);
    assertThat(result.getPoints()).as("the result 'score' has no field of its name").isNull();
  }

  @Test
  void wholeModel_everyResultIsMappedToItsField(BpmClient client) {
    MockRuntime.model("mock-all", "any", "amount");
    MockRuntime.onEvaluate(call -> MockRuntime.results(3, """
        {"approved":true,"nextStep":"Route to CFO","dueDate":"2026-12-15","deliveredAt":"2026-12-01T10:30:00",
         "upgrade":"SILVER","recommendation":{"product":"Sofa","score":0.75,"validUntil":"2027-01-31"},
         "labels":["urgent","b2b"],"points":3,"discount":10,"unknown":"ignored"}
        """));

    TestData result = run(client, "wholeModel", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    assertThat(MockRuntime.calls("evaluate").get(0).body().has("decision")).isFalse();
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
  }

  @Test
  void structuredResult_fillsADataClass(BpmClient client) {
    MockRuntime.model("mock-recommend", "offer", "amount");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"offer\":{\"product\":\"Lamp\",\"score\":0.5}}"));

    TestData result = run(client, "structuredResult", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getRecommendation().getProduct()).isEqualTo("Lamp");
    assertThat(result.getRecommendation().getScore()).isEqualTo(0.5);
  }

  @Test
  void allSupportedTypes_areSentAsJson(BpmClient client) {
    MockRuntime.model("mock-types", "check", "customer", "amount", "quantity", "orderDate", "orderedAt",
        "openingTime", "deliveryTime", "positions");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"check\":true}"));
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
    JsonNode input = MockRuntime.calls("evaluate").get(0).body().get("input");
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
    MockRuntime.model("mock-discount", "discount", "amount");
    MockRuntime.onEvaluate(call -> MockRuntime.error(422, "EVALUATION_FAILED",
        "Evaluation of model 'mock-discount' version 1 failed: the value is not allowed"));

    TestData result = run(client, "byName", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.DMN);
    assertThat(result.getErrorMessage()).contains("the value is not allowed");
    assertThat(result.getDiscount()).isNull();
  }

  @Test
  void unknownModel_raisesBpmnError(BpmClient client) {
    TestData result = run(client, "byName", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.DMN);
    assertThat(result.getErrorMessage()).contains("mock-discount");
  }

  @Test
  void runtimeNotReachable_raisesBpmnError(BpmClient client, AppFixture fixture) {
    fixture.var(Var.URL, "http://localhost:9");
    fixture.var(Var.CONNECT_TIMEOUT_SECONDS, "2");

    TestData result = run(client, "byName", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.UNREACHABLE);
    assertThat(result.getErrorMessage()).contains("http://localhost:9").contains(Var.URL);
  }

  @Test
  void timeout_raisesBpmnError(BpmClient client, AppFixture fixture) {
    fixture.var(Var.TIMEOUT_SECONDS, "1");
    MockRuntime.model("mock-slow", "slow");
    MockRuntime.onEvaluate(call -> {
      sleep(4000);
      return MockRuntime.results(1, "{\"slow\":1}");
    });

    long start = System.currentTimeMillis();
    TestData result = run(client, "timeout", sampleData());
    long duration = System.currentTimeMillis() - start;

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.TIMEOUT);
    assertThat(duration).as("RuleEngine.TimeoutSeconds is 1 second").isLessThan(3500);
  }

  @Test
  void inputWithoutField_raisesMappingError(BpmClient client) {
    MockRuntime.model("mock-discount", "discount", "amount", "creditScore");

    TestData result = run(client, "byName", sampleData());

    assertThat(result.getErrorCode()).isEqualTo(RuleErrors.MAPPING);
    assertThat(result.getErrorMessage()).contains("[creditScore]").contains("in.creditScore, set before the Business Rule Task");
    assertThat(MockRuntime.calls("evaluate")).isEmpty();
  }

  @Test
  void resultThatDoesNotFitTheField_raisesMappingError(BpmClient client) {
    MockRuntime.model("mock-discount", "discount", "amount");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"discount\":\"a lot\"}"));

    TestData result = run(client, "byName", sampleData());

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
  void dmnFileOfTheProject_isRegisteredAsVersion_once(BpmClient client) {
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"discount\":15}"));
    var data = sampleData();
    data.setType("Gold");

    TestData first = run(client, "demoDiscount", data);
    TestData second = run(client, "demoDiscount", data);

    assertThat(first.getErrorMessage()).isNull();
    assertThat(first.getDiscount().intValue()).isEqualTo(15);
    assertThat(second.getDiscount().intValue()).isEqualTo(15);
    var registrations = MockRuntime.calls("register");
    assertThat(registrations).as("unchanged content is registered once").hasSize(1);
    assertThat(registrations.get(0).model()).isEqualTo("discount");
    assertThat(registrations.get(0).body().get("source").asString()).isEqualTo("PROJECT");
    assertThat(registrations.get(0).body().get("content").asString()).contains("<definitions").contains("discount");
    assertThat(registrations.get(0).body().has("activate")).as("the runtime decides what goes live").isFalse();
    JsonNode input = MockRuntime.calls("evaluate").get(0).body().get("input");
    assertThat(input.propertyNames()).as("the inputs of dmn/discount.dmn")
        .containsExactlyInAnyOrder("purchaseAmount", "type", "country");
    assertThat(input.get("type").asString()).isEqualTo("Gold");
  }

  @Test
  void versionFromThePortal_getsTheInputsItNeeds(BpmClient client) {
    MockRuntime.model("discount", "discount", "purchaseAmount", "type", "country", "segment");
    MockRuntime.activeSource("discount", "PORTAL");
    MockRuntime.onEvaluate(call -> MockRuntime.results(2, "{\"discount\":20}"));

    TestData result = run(client, "demoDiscount", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getDiscount().intValue()).isEqualTo(20);
    JsonNode input = MockRuntime.calls("evaluate").get(0).body().get("input");
    assertThat(input.propertyNames()).as("the inputs of the file and the one the Portal version added")
        .containsExactlyInAnyOrder("purchaseAmount", "type", "country", "segment");
    assertThat(input.get("segment").asString()).as("found in the process data by its name").isEqualTo("GOLD");
  }

  @Test
  void versionActivatedInThePortalAMomentAgo_getsItsInputs(BpmClient client) {
    MockRuntime.model("discount", "discount", "purchaseAmount", "type", "country", "segment");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"discount\":15}"));
    run(client, "demoDiscount", sampleData());
    run(client, "demoDiscount", sampleData());
    MockRuntime.activeVersion("discount", 2);
    MockRuntime.activeSource("discount", "PORTAL");
    MockRuntime.onEvaluate(call -> MockRuntime.results(2, "{\"discount\":20}"));

    TestData result = run(client, "demoDiscount", sampleData());

    assertThat(result.getDiscount().intValue()).isEqualTo(20);
    var evaluations = MockRuntime.calls("evaluate");
    assertThat(evaluations).as("one for each of the first two runs, two for the third").hasSize(4);
    assertThat(evaluations.get(2).body().get("input").propertyNames()).doesNotContain("segment");
    assertThat(evaluations.get(3).body().get("input").propertyNames()).as("evaluated again with what version 2 needs")
        .contains("segment");
  }

  @Test
  void versionFromTheProject_isReplaced_theFileDecidesTheInputs(BpmClient client) {
    MockRuntime.model("discount", "discount", "purchaseAmount", "type", "country", "segment");
    MockRuntime.activeSource("discount", "PROJECT");
    MockRuntime.onEvaluate(call -> MockRuntime.results(2, "{\"discount\":15}"));

    run(client, "demoDiscount", sampleData());

    JsonNode input = MockRuntime.calls("evaluate").get(0).body().get("input");
    assertThat(input.propertyNames()).containsExactlyInAnyOrder("purchaseAmount", "type", "country");
  }

  @Test
  void modelPrefix_namesTheModelOnTheRuntime(BpmClient client, AppFixture fixture) {
    fixture.var(Var.MODEL_PREFIX, "sales-");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"discount\":15}"));

    run(client, "demoDiscount", sampleData());

    assertThat(MockRuntime.calls("register").get(0).model()).isEqualTo("sales-discount");
    assertThat(MockRuntime.calls("evaluate").get(0).model()).isEqualTo("sales-discount");
  }

  @Test
  void runtimeThatLostTheModel_getsItAgain(BpmClient client) {
    var evaluations = new AtomicInteger();
    MockRuntime.onEvaluate(call -> evaluations.incrementAndGet() == 2
        ? MockRuntime.error(404, "MODEL_NOT_FOUND", "Model 'discount' is not loaded.")
        : MockRuntime.results(1, "{\"discount\":15}"));

    run(client, "demoDiscount", sampleData());
    TestData afterRuntimeWasReset = run(client, "demoDiscount", sampleData());

    assertThat(afterRuntimeWasReset.getErrorMessage()).isNull();
    assertThat(afterRuntimeWasReset.getDiscount().intValue()).isEqualTo(15);
    assertThat(MockRuntime.calls("register")).as("registered again after MODEL_NOT_FOUND").hasSize(2);
    assertThat(MockRuntime.calls("evaluate")).hasSize(3);
  }

  @Test
  void autoDeployOff_doesNotRegister(BpmClient client, AppFixture fixture) {
    fixture.var(Var.AUTO_DEPLOY, "false");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"discount\":15}"));

    TestData result = run(client, "demoDiscount", sampleData());

    assertThat(result.getErrorMessage()).isNull();
    assertThat(MockRuntime.calls("register")).isEmpty();
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
