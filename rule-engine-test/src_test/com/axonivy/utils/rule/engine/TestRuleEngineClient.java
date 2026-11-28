package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;
import com.axonivy.utils.rule.engine.test.mock.MockDmnEngine;
import com.axonivy.utils.rule.engine.test.mock.TestStore;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import jakarta.ws.rs.core.Response;

@IvyProcessTest(enableWebServer = true)
class TestRuleEngineClient {

  private static final String DISCOUNT = TestStore.dmn("discount", "discount", "purchaseAmount", "type");

  @BeforeEach
  void setup() {
    MockDmnEngine.reset();
  }

  private static RuleEngineClient client() {
    return RuleEngineClient.builder().baseUrl(MockDmnEngine.url()).readTimeout(Duration.ofSeconds(3)).build();
  }

  @Test
  void evaluate_sendsTheDmnFileAndTheInput() {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.json(200, """
        {"decisionResults":[{"decisionId":"_d","decisionName":"discount","result":15,"evaluationStatus":"SUCCEEDED",
          "messages":[{"severity":"WARN","message":"DMN: careful","messageType":"SOME","sourceId":"_d","level":"WARNING"}],
          "evaluationHitIds":{"_rule1":1}}],
         "messages":[{"severity":"WARN","message":"DMN: careful"}],"dmnContext":{},"somethingNew":true}
        """));
    var input = new LinkedHashMap<String, Object>();
    input.put("purchaseAmount", 1500);
    input.put("type", null);

    var result = client().evaluate(DISCOUNT, input);

    assertThat(result.decisionNames()).containsExactly("discount");
    var decision = result.decision("discount").orElseThrow();
    assertThat(decision.succeeded()).isTrue();
    assertThat(decision.result()).isEqualTo(15);
    assertThat(decision.messages()).extracting(Message::message).as("without the prefix of the engine")
        .containsExactly("careful");
    var call = MockDmnEngine.calls("evaluate").get(0);
    assertThat(call.dmn()).isEqualTo(DISCOUNT);
    assertThat(call.input().get("purchaseAmount").asInt()).isEqualTo(1500);
    assertThat(call.input().get("type").isNull()).as("null inputs are sent").isTrue();
  }

  @Test
  void validate_sendsTheDmnFile_andAnswersTheFindings() {
    MockDmnEngine.onValidate(call -> MockDmnEngine.json(200, """
        [{"severity":"WARN","message":"Overlapping rules","messageType":"DECISION_TABLE_HITPOLICY_RECOMMENDER"},
         {"severity":"ERROR","message":"DMN: Missing output","sourceId":"_rule3"}]"""));

    var result = client().validate(DISCOUNT);

    assertThat(result.valid()).isFalse();
    assertThat(result.messages()).extracting(Message::severity, Message::message, Message::sourceId)
        .containsExactly(tuple("WARN", "Overlapping rules", null), tuple("ERROR", "Missing output", "_rule3"));
    assertThat(MockDmnEngine.calls("validate").get(0).dmn()).isEqualTo(DISCOUNT);

    MockDmnEngine.reset();
    assertThat(client().validate(DISCOUNT).valid()).as("no findings").isTrue();
    assertThat(client().isReady()).isTrue();
  }

  @Test
  void errorAnswer_becomesApiException() {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.unreadable("local part cannot be \"null\" when creating a QName"));
    assertThatThrownBy(() -> client().evaluate("<definitions/>", Map.of()))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.statusCode()).isEqualTo(400);
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_MODEL);
          assertThat(ex.getMessage()).contains("cannot read the model").contains("QName");
        });

    MockDmnEngine.onEvaluate(call -> Response.status(502).type("text/html").entity("<html>Bad Gateway</html>").build());
    assertThatThrownBy(() -> client().evaluate(DISCOUNT, Map.of()))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.statusCode()).isEqualTo(502);
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.UNKNOWN);
          assertThat(ex.getMessage()).contains("502").contains("Bad Gateway");
        });
  }

  @Test
  void transportFailures_becomeTimeoutOrUnreachable() {
    MockDmnEngine.onEvaluate(call -> {
      sleep(3000);
      return MockDmnEngine.results("{}");
    });
    var impatient = RuleEngineClient.builder().baseUrl(MockDmnEngine.url()).readTimeout(Duration.ofSeconds(1)).build();
    assertThatThrownBy(() -> impatient.evaluate(DISCOUNT, Map.of()))
        .isInstanceOf(RuleEngineTimeoutException.class)
        .hasMessageContaining(MockDmnEngine.url());

    var nobody = RuleEngineClient.builder().baseUrl("http://localhost:9").connectTimeout(Duration.ofSeconds(2)).build();
    assertThatThrownBy(() -> nobody.validate(DISCOUNT))
        .isInstanceOf(RuleEngineUnreachableException.class)
        .hasMessageContaining("http://localhost:9")
        .hasMessageContaining("RuleEngine.Url");
    assertThat(nobody.isReady()).isFalse();
  }

  @Test
  void logRequests_keepsRequestAndAnswer() {
    MockDmnEngine.onEvaluate(call -> MockDmnEngine.results("{\"discount\":15}"));
    var logging = RuleEngineClient.builder().baseUrl(MockDmnEngine.url()).logRequests(true).build();

    var result = logging.evaluate(DISCOUNT, Map.of("purchaseAmount", 1500));

    assertThat(result.decision("discount").orElseThrow().result()).isEqualTo(15);
    assertThat(MockDmnEngine.calls("evaluate").get(0).dmn()).isEqualTo(DISCOUNT);
  }

  @Test
  void baseUrlIsRequired() {
    assertThatThrownBy(() -> RuleEngineClient.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
