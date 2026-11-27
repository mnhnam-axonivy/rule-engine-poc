package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.test.mock.MockDmnEngine;
import com.axonivy.utils.rule.engine.test.mock.TestStore;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;

/**
 * The DMN engine evaluates every decision of a model; the connector answers with the one that was asked for.
 */
@IvyProcessTest(enableWebServer = true)
class TestEvaluator {

  private static final String DISCOUNT = TestStore.dmn("discount", "discount", "purchaseAmount");

  @BeforeEach
  void setup(AppFixture fixture) {
    MockDmnEngine.reset();
    TestStore.clear();
    fixture.var(Var.URL, MockDmnEngine.url());
    TestStore.store("discount", DISCOUNT, VersionSource.API, null);
  }

  @Test
  void theAskedDecision_answers_whateverTheOthersDo() {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.json(200, """
        {"decisionResults":[
          {"decisionName":"discount","result":15,"evaluationStatus":"SUCCEEDED","messages":[]},
          {"decisionName":"bonus","result":null,"evaluationStatus":"FAILED",
           "messages":[{"severity":"ERROR","message":"Required dependency 'tier' not found on node 'bonus'"}]}],
         "messages":[{"severity":"ERROR","message":"Required dependency 'tier' not found on node 'bonus'"}]}"""));

    var response = RuleEngine.evaluate("discount", "discount", Map.of("purchaseAmount", 1500));

    assertThat(response.model()).isEqualTo("discount");
    assertThat(response.version()).isEqualTo(1);
    assertThat(response.decision()).isEqualTo("discount");
    assertThat(response.result()).isEqualTo(15);
    assertThat(response.results()).containsOnlyKeys("discount");
    assertThat(response.messages()).as("the findings of the other decision do not count").isEmpty();
    assertThat(MockDmnEngine.calls("evaluate").get(0).dmn()).as("the file of the active version")
        .isEqualTo(VersionStore.current().content("discount", 1));
  }

  @Test
  void aFailedDecision_failsTheEvaluation() {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.failed("discount",
        "Required dependency 'purchaseAmount' not found on node 'discount'"));

    assertThatThrownBy(() -> RuleEngine.evaluate("discount", "discount", Map.of()))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.EVALUATION_FAILED);
          assertThat(ex.getMessage()).isEqualTo("Evaluation of version 1 of model 'discount' failed: "
              + "Required dependency 'purchaseAmount' not found on node 'discount'");
          assertThat(ex.details()).hasSize(1);
        });
  }

  @Test
  void anUnknownDecision_isNotFound() {
    assertThatThrownBy(() -> RuleEngine.evaluate("discount", "bonus", Map.of()))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.DECISION_NOT_FOUND);
          assertThat(ex.getMessage()).isEqualTo("Version 1 of model 'discount' has no decision 'bonus'. "
              + "Its decisions are: [discount]");
        });
  }

  @Test
  void allDecisions_needEveryOneOfThem() {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":15,\"bonus\":2}"));
    var response = RuleEngine.evaluate("discount", null, Map.of("purchaseAmount", 1500));
    assertThat(response.decision()).isNull();
    assertThat(response.results()).containsEntry("discount", 15).containsEntry("bonus", 2);

    MockDmnEngine.onEvaluate(call -> MockDmnEngine.failed("bonus", "Required dependency 'tier' not found"));
    assertThatThrownBy(() -> RuleEngine.evaluate("discount", null, Map.of()))
        .isInstanceOfSatisfying(RuleEngineApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.EVALUATION_FAILED));
  }

  @Test
  void aVersionThatIsNotActive_canBeEvaluated() {
    TestStore.store("discount", TestStore.dmn("discount", "discount", "purchaseAmount", "tier"), VersionSource.PORTAL,
        false);

    var response = RuleEngine.evaluate("discount", 2, "discount", Map.of());

    assertThat(response.version()).isEqualTo(2);
    assertThat(MockDmnEngine.calls("evaluate").get(0).dmn()).isEqualTo(VersionStore.current().content("discount", 2));
  }
}
