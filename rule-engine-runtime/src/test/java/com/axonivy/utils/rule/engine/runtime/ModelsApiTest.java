package com.axonivy.utils.rule.engine.runtime;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

@QuarkusTest
class ModelsApiTest {

  @Test
  void startsEmpty_andLoadsDmnViaApi() {
    String model = TestModels.uniqueName("load");
    evaluate(model, 1, gold(1500)).statusCode(404).body("code", equalTo("MODEL_NOT_FOUND"));

    loadXml(model, 1, TestModels.discount(15))
        .statusCode(200)
        .body("model", equalTo(model))
        .body("version", equalTo(1))
        .body("dmnName", equalTo("discount"))
        .body("contentHash", not(nullValue()))
        .body("decisions.name", hasItem("discount"))
        .body("inputs.name", hasItem("purchaseAmount"));

    evaluate(model, 1, gold(1500))
        .statusCode(200)
        .body("model", equalTo(model))
        .body("version", equalTo(1))
        .body("results.discount", equalTo(15));
    given().get("/api/models").then().statusCode(200).body("model", hasItem(model));
  }

  @Test
  void evaluatesNamedDecision_andReturnsItsResult() {
    String model = TestModels.uniqueName("named");
    loadXml(model, 1, TestModels.discount(15)).statusCode(200);

    given().contentType(ContentType.JSON)
        .body(Map.of("decision", "discount", "input", gold(1500)))
        .post("/api/models/{model}/versions/1/evaluate", model).then()
        .statusCode(200)
        .body("decision", equalTo("discount"))
        .body("result", equalTo(15))
        .body("results.discount", equalTo(15));

    given().contentType(ContentType.JSON)
        .body(Map.of("decision", "unknown", "input", gold(1500)))
        .post("/api/models/{model}/versions/1/evaluate", model).then()
        .statusCode(404)
        .body("code", equalTo("DECISION_NOT_FOUND"))
        .body("message", containsString("discount"));
  }

  @Test
  void eachVersionAnswersWithItsOwnRules() {
    String model = TestModels.uniqueName("versions");
    loadXml(model, 1, TestModels.discount(15)).statusCode(200);
    load(model, 2, TestModels.discount(20)).statusCode(200).body("version", equalTo(2));

    evaluate(model, 1, gold(1500)).body("version", equalTo(1)).body("results.discount", equalTo(15));
    evaluate(model, 2, gold(1500)).body("version", equalTo(2)).body("results.discount", equalTo(20));
    evaluate(model, 3, gold(1500)).statusCode(404).body("code", equalTo("VERSION_NOT_FOUND"));
  }

  @Test
  void loadingAgain_replacesTheVersion() {
    String model = TestModels.uniqueName("replace");
    String first = loadXml(model, 1, TestModels.discount(15)).extract().path("contentHash");
    // line endings do not change the content
    loadXml(model, 1, TestModels.discount(15).replace("\n", "\r\n")).body("contentHash", equalTo(first));

    loadXml(model, 1, TestModels.discount(20)).body("contentHash", not(equalTo(first)));
    evaluate(model, 1, gold(1500)).body("results.discount", equalTo(20));
  }

  @Test
  void invalidDmnIsRejected_withReadableMessage() {
    String model = TestModels.uniqueName("invalid");
    loadXml(model, 1, "this is not a dmn file")
        .statusCode(400)
        .body("code", equalTo("INVALID_MODEL"))
        .body("message", containsString("DMN"));
    loadXml(model, 1, TestModels.brokenExpression())
        .statusCode(400)
        .body("code", equalTo("INVALID_MODEL"))
        .body("details", not(hasSize(0)))
        .body("details.severity", hasItem("ERROR"));
    loadXml(model, 1, TestModels.missingInput())
        .statusCode(400)
        .body("code", equalTo("INVALID_MODEL"));
    load(model, 1, " ").statusCode(400).body("code", equalTo("INVALID_REQUEST"));
    loadXml("bad name!", 1, TestModels.discount(15)).statusCode(400).body("code", equalTo("INVALID_REQUEST"));
    loadXml(model, 0, TestModels.discount(15)).statusCode(400).body("code", equalTo("INVALID_REQUEST"));
    evaluate(model, 1, gold(1500)).statusCode(404).body("code", equalTo("MODEL_NOT_FOUND"));

    given().contentType(ContentType.XML).body(TestModels.brokenExpression())
        .post("/api/validate").then()
        .statusCode(200)
        .body("valid", equalTo(false))
        .body("messages.severity", hasItem("ERROR"));
    given().contentType(ContentType.XML).body(TestModels.discount(15))
        .post("/api/validate").then()
        .statusCode(200)
        .body("valid", equalTo(true));
  }

  @Test
  void missingInputFailsEvaluation() {
    String model = TestModels.uniqueName("missing");
    loadXml(model, 1, TestModels.discount(15)).statusCode(200);
    evaluate(model, 1, Map.of("purchaseAmount", 1500))
        .statusCode(422)
        .body("code", equalTo("EVALUATION_FAILED"))
        .body("message", containsString("type"));
    // an explicit null is a value: no rule for Gold matches, the default applies
    var input = new HashMap<String, Object>();
    input.put("purchaseAmount", 1500);
    input.put("type", null);
    evaluate(model, 1, input).statusCode(200).body("results.discount", equalTo(0));
  }

  @Test
  void datesAreConvertedBothWays() {
    String model = TestModels.uniqueName("dates");
    loadXml(model, 1, TestModels.dueDate()).statusCode(200);
    evaluate(model, 1, Map.of("invoiceDate", "2026-11-15"))
        .statusCode(200)
        .body("results.dueDate", equalTo("2026-12-15"));
  }

  @Test
  void unload_removesAllVersionsOfTheModel() {
    String model = TestModels.uniqueName("unload");
    loadXml(model, 1, TestModels.discount(10)).statusCode(200);
    loadXml(model, 2, TestModels.discount(20)).statusCode(200);
    given().delete("/api/models/{model}", model).then().statusCode(204);
    evaluate(model, 2, gold(1500)).statusCode(404).body("code", equalTo("MODEL_NOT_FOUND"));
    assertThat(given().get("/api/models").then().extract().jsonPath().getList("model", String.class))
        .doesNotContain(model);
  }

  @Test
  void healthAndContractAreAvailable() {
    given().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    given().get("/q/health/ready").then()
        .statusCode(200)
        .body("status", equalTo("UP"))
        .body("checks.name", hasItem("Loaded DMN models"));
    given().get("/q/openapi").then().statusCode(200)
        .body(containsString("/api/models/{model}/versions/{version}/evaluate"));
  }

  @Test
  void noMessagesOnCleanEvaluation() {
    String model = TestModels.uniqueName("clean");
    loadXml(model, 1, TestModels.discount(15)).statusCode(200);
    evaluate(model, 1, gold(10)).statusCode(200).body("messages", nullValue()).body("results.discount", equalTo(0));
  }

  private static Map<String, Object> gold(int amount) {
    return Map.of("purchaseAmount", amount, "type", "Gold");
  }

  private static ValidatableResponse loadXml(String model, int version, String dmn) {
    return given().contentType(ContentType.XML).body(dmn)
        .put("/api/models/{model}/versions/{version}", model, version).then();
  }

  private static ValidatableResponse load(String model, int version, String dmn) {
    return given().contentType(ContentType.JSON).body(Map.of("content", dmn))
        .put("/api/models/{model}/versions/{version}", model, version).then();
  }

  private static ValidatableResponse evaluate(String model, int version, Map<String, Object> input) {
    var body = new HashMap<String, Object>();
    body.put("input", input);
    return given().contentType(ContentType.JSON).body(body)
        .post("/api/models/{model}/versions/{version}/evaluate", model, version).then();
  }
}
