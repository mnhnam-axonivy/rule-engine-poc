package com.axonivy.utils.rule.engine.runtime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

@QuarkusTest
@TestProfile(ApiKeyTest.Secured.class)
class ApiKeyTest {

  public static class Secured implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("rule.engine.api-key", "s3cret");
    }
  }

  @Test
  void apiNeedsTheKey() {
    given().get("/api/models").then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
    given().header("X-API-Key", "wrong").get("/api/models").then().statusCode(401);
    given().contentType(ContentType.XML).body(TestModels.discount(15))
        .post("/api/models/secured/versions").then().statusCode(401);

    given().header("X-API-Key", "s3cret").get("/api/models").then().statusCode(200);
    given().header("Authorization", "Bearer s3cret").get("/api/models").then().statusCode(200);
  }

  @Test
  void healthStaysOpen() {
    given().get("/q/health").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  void editorPageStaysOpen() {
    // the page itself holds no data: it asks for the key when the API refuses it
    given().get("/editor/").then().statusCode(200);
    given().get("/editor/editor.js").then().statusCode(200);
  }
}
