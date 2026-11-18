package com.axonivy.utils.rule.engine.runtime;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.runtime.service.ModelRegistry;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;

@QuarkusTest
class ModelsApiTest {

  @Inject
  ModelRegistry registry;

  @Test
  void startsWithoutModel_andLoadsDmnViaApi() {
    String model = TestModels.uniqueName("load");
    given().get("/api/models/{model}", model).then()
        .statusCode(404)
        .body("code", equalTo("MODEL_NOT_FOUND"));
    evaluate(model, gold(1500)).statusCode(404).body("code", equalTo("MODEL_NOT_FOUND"));

    uploadXml(model, TestModels.discount(15))
        .statusCode(201)
        .body("version", equalTo(1))
        .body("status", equalTo("ACTIVE"))
        .body("created", equalTo(true))
        .body("dmnName", equalTo("discount"));

    evaluate(model, gold(1500))
        .statusCode(200)
        .body("model", equalTo(model))
        .body("version", equalTo(1))
        .body("results.discount", equalTo(15));
    given().get("/api/models").then().statusCode(200).body("name", hasItem(model));
    given().get("/api/models/{model}", model).then()
        .statusCode(200)
        .body("activeVersion", equalTo(1))
        .body("decisions.name", hasItem("discount"))
        .body("inputs.name", hasItem("purchaseAmount"));
  }

  @Test
  void evaluatesNamedDecision_andReturnsItsResult() {
    String model = TestModels.uniqueName("named");
    uploadXml(model, TestModels.discount(15)).statusCode(201);

    given().contentType(ContentType.JSON)
        .body(Map.of("decision", "discount", "input", gold(1500)))
        .post("/api/models/{model}/evaluate", model).then()
        .statusCode(200)
        .body("decision", equalTo("discount"))
        .body("result", equalTo(15))
        .body("results.discount", equalTo(15));

    given().contentType(ContentType.JSON)
        .body(Map.of("decision", "unknown", "input", gold(1500)))
        .post("/api/models/{model}/evaluate", model).then()
        .statusCode(404)
        .body("code", equalTo("DECISION_NOT_FOUND"))
        .body("message", containsString("discount"));
  }

  @Test
  void newVersionReplacesOldOne_withoutRestart() {
    String model = TestModels.uniqueName("replace");
    uploadXml(model, TestModels.discount(15)).statusCode(201);
    evaluate(model, gold(1500)).body("results.discount", equalTo(15)).body("version", equalTo(1));

    register(model, TestModels.discount(20), Map.of("activate", true))
        .statusCode(201)
        .body("version", equalTo(2))
        .body("status", equalTo("ACTIVE"))
        .body("previousVersion", equalTo(1));

    evaluate(model, gold(1500)).body("results.discount", equalTo(20)).body("version", equalTo(2));
    version(model, 1).body("status", equalTo("RETIRED"));
  }

  @Test
  void severalVersionsAreLoaded_exactlyOneIsActive() {
    String model = TestModels.uniqueName("several");
    uploadXml(model, TestModels.discount(15)).statusCode(201).body("status", equalTo("ACTIVE"));
    uploadXml(model, TestModels.discount(20)).statusCode(201).body("status", equalTo("DRAFT"));
    uploadXml(model, TestModels.discount(25)).statusCode(201).body("status", equalTo("DRAFT"));

    var statuses = given().get("/api/models/{model}/versions", model).then()
        .statusCode(200)
        .body("$", hasSize(3))
        .extract().jsonPath().getList("status", String.class);
    assertThat(statuses).containsExactly("DRAFT", "DRAFT", "ACTIVE");

    evaluate(model, gold(1500)).body("results.discount", equalTo(15)).body("version", equalTo(1));
    // a draft can be evaluated explicitly for a test run
    given().contentType(ContentType.JSON).body(Map.of("input", gold(1500)))
        .post("/api/models/{model}/versions/3/evaluate", model).then()
        .statusCode(200)
        .body("version", equalTo(3))
        .body("results.discount", equalTo(25));
  }

  @Test
  void sameContentIsNotStoredTwice() {
    String model = TestModels.uniqueName("same");
    uploadXml(model, TestModels.discount(15)).statusCode(201);
    uploadXml(model, TestModels.discount(15))
        .statusCode(200)
        .body("version", equalTo(1))
        .body("created", equalTo(false));
    // line endings do not make a new version
    uploadXml(model, TestModels.discount(15).replace("\n", "\r\n"))
        .statusCode(200)
        .body("version", equalTo(1));
    given().get("/api/models/{model}/versions", model).then().body("$", hasSize(1));
  }

  @Test
  void invalidDmnIsRejected_withReadableMessage() {
    String model = TestModels.uniqueName("invalid");
    uploadXml(model, "this is not a dmn file")
        .statusCode(400)
        .body("code", equalTo("INVALID_MODEL"))
        .body("message", containsString("DMN"));
    uploadXml(model, TestModels.brokenExpression())
        .statusCode(400)
        .body("code", equalTo("INVALID_MODEL"))
        .body("details", not(hasSize(0)))
        .body("details.severity", hasItem("ERROR"));
    uploadXml(model, TestModels.missingInput())
        .statusCode(400)
        .body("code", equalTo("INVALID_MODEL"));
    register(model, " ", Map.of()).statusCode(400).body("code", equalTo("INVALID_REQUEST"));
    uploadXml("bad name!", TestModels.discount(15)).statusCode(400).body("code", equalTo("INVALID_REQUEST"));
    // nothing was stored
    given().get("/api/models/{model}", model).then().statusCode(404);

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
    uploadXml(model, TestModels.discount(15)).statusCode(201);
    evaluate(model, Map.of("purchaseAmount", 1500))
        .statusCode(422)
        .body("code", equalTo("EVALUATION_FAILED"))
        .body("message", containsString("type"));
    // an explicit null is a value: no rule for Gold matches, the default applies
    var input = new java.util.HashMap<String, Object>();
    input.put("purchaseAmount", 1500);
    input.put("type", null);
    evaluate(model, input).statusCode(200).body("results.discount", equalTo(0));
  }

  @Test
  void rollbackActivatesPreviousVersion() {
    String model = TestModels.uniqueName("rollback");
    uploadXml(model, TestModels.discount(10)).statusCode(201);
    register(model, TestModels.discount(20), Map.of("activate", true)).statusCode(201);
    register(model, TestModels.discount(30), Map.of("activate", true)).statusCode(201);
    evaluate(model, gold(1500)).body("version", equalTo(3));

    given().post("/api/models/{model}/rollback", model).then()
        .statusCode(200)
        .body("version", equalTo(2))
        .body("status", equalTo("ACTIVE"));
    evaluate(model, gold(1500)).body("version", equalTo(2)).body("results.discount", equalTo(20));
    version(model, 3).body("status", equalTo("RETIRED"));

    given().post("/api/models/{model}/rollback", model).then().statusCode(200).body("version", equalTo(1));
    evaluate(model, gold(1500)).body("version", equalTo(1)).body("results.discount", equalTo(10));

    given().post("/api/models/{model}/rollback", model).then()
        .statusCode(409)
        .body("code", equalTo("INVALID_STATE"));
  }

  @Test
  void projectRedeployment_replacesProjectVersion() {
    String model = TestModels.uniqueName("project");
    register(model, TestModels.discount(10), Map.of("source", "PROJECT")).statusCode(201);
    register(model, TestModels.discount(20), Map.of("source", "PROJECT"))
        .statusCode(201)
        .body("status", equalTo("ACTIVE"));
    evaluate(model, gold(1500)).body("version", equalTo(2));
    // deploying an older state of the project again makes that state live again
    register(model, TestModels.discount(10), Map.of("source", "PROJECT"))
        .statusCode(200)
        .body("version", equalTo(1))
        .body("status", equalTo("ACTIVE"));
  }

  @Test
  void projectRedeployment_doesNotOverwriteVersionActivatedFromPortal() {
    String model = TestModels.uniqueName("portal");
    register(model, TestModels.discount(10), Map.of("source", "PROJECT")).statusCode(201);
    register(model, TestModels.discount(20), Map.of("source", "PORTAL", "author", "rule.owner"))
        .statusCode(201)
        .body("status", equalTo("DRAFT"));
    given().post("/api/models/{model}/versions/2/approve", model).then()
        .statusCode(200).body("status", equalTo("APPROVED"));
    given().contentType(ContentType.JSON).body(Map.of("user", "rule.approver"))
        .post("/api/models/{model}/versions/2/activate", model).then()
        .statusCode(200)
        .body("status", equalTo("ACTIVE"))
        .body("activatedBy", equalTo("rule.approver"));

    // the project is deployed again with a changed file: it is registered, but does not go live
    register(model, TestModels.discount(30), Map.of("source", "PROJECT"))
        .statusCode(201)
        .body("version", equalTo(3))
        .body("status", equalTo("DRAFT"));
    // and neither does the unchanged file of the initial deployment
    register(model, TestModels.discount(10), Map.of("source", "PROJECT"))
        .statusCode(200)
        .body("version", equalTo(1))
        .body("status", equalTo("RETIRED"));
    evaluate(model, gold(1500)).body("version", equalTo(2)).body("results.discount", equalTo(20));
  }

  @Test
  void versionGoesLiveAtEffectiveDate() throws InterruptedException {
    String model = TestModels.uniqueName("effective");
    uploadXml(model, TestModels.discount(10)).statusCode(201);
    var effective = OffsetDateTime.now().plusSeconds(2);
    register(model, TestModels.discount(20), Map.of("effectiveDate", effective.toString()))
        .statusCode(201)
        .body("status", equalTo("APPROVED"))
        .body("effectiveDate", notNullValue());
    evaluate(model, gold(1500)).body("version", equalTo(1));

    var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    int version = 1;
    while (version == 1 && System.nanoTime() < deadline) {
      Thread.sleep(250);
      version = evaluate(model, gold(1500)).extract().path("version");
    }
    assertThat(version).as("the scheduled version becomes active without any further call").isEqualTo(2);
    assertThat(OffsetDateTime.now()).isAfterOrEqualTo(effective);
  }

  @Test
  void retire_onlyVersionsThatAreNotActive() {
    String model = TestModels.uniqueName("retire");
    uploadXml(model, TestModels.discount(10)).statusCode(201);
    uploadXml(model, TestModels.discount(20)).statusCode(201);
    given().post("/api/models/{model}/versions/1/retire", model).then()
        .statusCode(409).body("code", equalTo("INVALID_STATE"));
    given().post("/api/models/{model}/versions/2/retire", model).then()
        .statusCode(200).body("status", equalTo("RETIRED"));
    given().post("/api/models/{model}/versions/9/retire", model).then()
        .statusCode(404).body("code", equalTo("VERSION_NOT_FOUND"));
  }

  @Test
  void contentCanBeExported() {
    String model = TestModels.uniqueName("export");
    String dmn = TestModels.discount(15);
    uploadXml(model, dmn).statusCode(201);
    String exported = given().get("/api/models/{model}/versions/1/content", model).then()
        .statusCode(200)
        .extract().asString();
    assertThat(exported).isEqualTo(dmn);
  }

  @Test
  void datesAreConvertedBothWays() {
    String model = TestModels.uniqueName("dates");
    uploadXml(model, TestModels.dueDate()).statusCode(201);
    evaluate(model, Map.of("invoiceDate", "2026-11-15"))
        .statusCode(200)
        .body("results.dueDate", equalTo("2026-12-15"));
  }

  @Test
  void activeVersionsAreLoadedFromTheStore_afterRestart() {
    String model = TestModels.uniqueName("restart");
    uploadXml(model, TestModels.discount(10)).statusCode(201);
    register(model, TestModels.discount(20), Map.of("activate", true)).statusCode(201);

    // what a restart does: memory is empty, the active versions come back from the database
    registry.evict(model);
    assertThat(registry.active(model)).isEmpty();
    registry.sync();

    assertThat(registry.active(model)).isPresent();
    evaluate(model, gold(1500)).statusCode(200).body("version", equalTo(2)).body("results.discount", equalTo(20));
  }

  @Test
  void deleteRemovesModel() {
    String model = TestModels.uniqueName("delete");
    uploadXml(model, TestModels.discount(10)).statusCode(201);
    given().delete("/api/models/{model}", model).then().statusCode(204);
    evaluate(model, gold(1500)).statusCode(404);
    uploadXml(model, TestModels.discount(20)).statusCode(201).body("version", equalTo(1));
    evaluate(model, gold(1500)).body("results.discount", equalTo(20));
  }

  @Test
  void healthAndContractAreAvailable() {
    given().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    given().get("/q/health/ready").then()
        .statusCode(200)
        .body("status", equalTo("UP"))
        .body("checks.name", hasItem("Active DMN models"));
    given().get("/q/openapi").then().statusCode(200).body(containsString("/api/models/{model}/evaluate"));
  }

  @Test
  void noMessagesOnCleanEvaluation() {
    String model = TestModels.uniqueName("clean");
    uploadXml(model, TestModels.discount(15)).statusCode(201);
    evaluate(model, gold(10)).statusCode(200).body("messages", nullValue()).body("results.discount", equalTo(0));
  }

  private static Map<String, Object> gold(int amount) {
    return Map.of("purchaseAmount", amount, "type", "Gold");
  }

  private static ValidatableResponse uploadXml(String model, String dmn) {
    return given().contentType(ContentType.XML).body(dmn)
        .post("/api/models/{model}/versions", model).then();
  }

  private static ValidatableResponse register(String model, String dmn, Map<String, Object> options) {
    var body = new java.util.HashMap<String, Object>(options);
    body.put("content", dmn);
    return given().contentType(ContentType.JSON).body(body)
        .post("/api/models/{model}/versions", model).then();
  }

  private static ValidatableResponse evaluate(String model, Map<String, Object> input) {
    var body = new java.util.HashMap<String, Object>();
    body.put("input", input);
    return given().contentType(ContentType.JSON).body(body)
        .post("/api/models/{model}/evaluate", model).then();
  }

  private static ValidatableResponse version(String model, int version) {
    return given().get("/api/models/{model}/versions/{version}", model, version).then().statusCode(200);
  }
}
