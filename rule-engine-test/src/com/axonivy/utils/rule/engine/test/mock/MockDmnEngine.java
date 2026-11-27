package com.axonivy.utils.rule.engine.test.mock;

import java.util.List;
import java.util.function.Function;
import java.util.concurrent.CopyOnWriteArrayList;

import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;

import ch.ivyteam.ivy.environment.Ivy;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Like the Apache KIE JIT runner, it keeps nothing: every call carries the DMN file. Unless a test says otherwise, it
 * accepts every model, and every decision of a model succeeds without a result.
 */
@Path(MockDmnEngine.PATH)
@PermitAll // the connector calls without an Ivy session
@Hidden // not part of the API of this application
@SuppressWarnings("all")
public class MockDmnEngine {

  public static final String PATH = "dmnMock";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SUCCEEDED = "SUCCEEDED";
  private static final String FAILED = "FAILED";

  /** @param input the context of an evaluation, null for a check */
  public record Call(String operation, String dmn, JsonNode input) {}

  private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  private static volatile Function<Call, Response> evaluate;
  private static volatile Function<Call, Response> validate;

  /** @return the URL of the mock, to be used as variable RuleEngine.Url */
  public static String url() {
    return Ivy.rest().client("mockDmnEngine").getUri().toASCIIString();
  }

  public static void reset() {
    CALLS.clear();
    evaluate = call -> everyDecisionSucceeds(call.dmn());
    validate = call -> json(200, "[]");
  }

  public static void onEvaluate(Function<Call, Response> handler) {
    evaluate = handler;
  }

  public static void onValidate(Function<Call, Response> handler) {
    validate = handler;
  }

  public static List<Call> calls() {
    return List.copyOf(CALLS);
  }

  public static List<Call> calls(String operation) {
    return CALLS.stream().filter(call -> call.operation().equals(operation)).toList();
  }

  public static Response json(int status, String json) {
    return Response.status(status).type(MediaType.APPLICATION_JSON).entity(json).build();
  }

  /** @param resultsJson the result of each decision by its name, e.g. <code>{"discount":15}</code> */
  public static Response results(String resultsJson) {
    var answer = MAPPER.createObjectNode();
    ArrayNode decisions = answer.putArray("decisionResults");
    MAPPER.readTree(resultsJson).properties().forEach(entry -> decision(decisions, entry.getKey(), SUCCEEDED)
        .set("result", entry.getValue()));
    answer.putArray("messages");
    return json(200, MAPPER.writeValueAsString(answer));
  }

  /** The evaluation of the decision fails with this error, as when an input is missing. */
  public static Response failed(String decision, String error) {
    var answer = MAPPER.createObjectNode();
    var failed = decision(answer.putArray("decisionResults"), decision, FAILED);
    failed.putNull("result");
    finding(failed.putArray("messages"), "ERROR", error);
    finding(answer.putArray("messages"), "ERROR", error);
    return json(200, MAPPER.writeValueAsString(answer));
  }

  /** The answer of a check that finds these errors. */
  public static Response findings(String... errors) {
    var answer = MAPPER.createArrayNode();
    for (String error : errors) {
      finding(answer, "ERROR", error);
    }
    return json(200, MAPPER.writeValueAsString(answer));
  }

  /** What the engine answers for a file it cannot read. */
  public static Response unreadable(String message) {
    return Response.status(400).type(MediaType.TEXT_PLAIN).entity(message).build();
  }

  @GET
  @Path("q/health/ready")
  @Produces(MediaType.APPLICATION_JSON)
  public Response ready() {
    return json(200, "{\"status\":\"UP\"}");
  }

  @POST
  @Path("jitdmn/validate")
  @Consumes(MediaType.APPLICATION_XML)
  @Produces(MediaType.APPLICATION_JSON)
  public Response validate(String dmn) {
    var call = new Call("validate", dmn, null);
    CALLS.add(call);
    return validate.apply(call);
  }

  @POST
  @Path("jitdmn/dmnresult")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response evaluate(String body) {
    JsonNode request = MAPPER.readTree(body);
    var call = new Call("evaluate", request.get("model").asString(), request.get("context"));
    CALLS.add(call);
    return evaluate.apply(call);
  }

  private static Response everyDecisionSucceeds(String dmn) {
    DmnModelInfo info;
    try {
      info = DmnModelInfo.parse(dmn);
    } catch (IllegalArgumentException ex) {
      return unreadable(ex.getMessage());
    }
    var answer = MAPPER.createObjectNode();
    ArrayNode decisions = answer.putArray("decisionResults");
    info.decisions().forEach(decision -> decision(decisions, decision.name(), SUCCEEDED).putNull("result"));
    answer.putArray("messages");
    return json(200, MAPPER.writeValueAsString(answer));
  }

  private static ObjectNode decision(ArrayNode decisions, String name, String status) {
    var decision = decisions.addObject();
    decision.put("decisionName", name);
    decision.put("evaluationStatus", status);
    decision.putArray("messages");
    return decision;
  }

  private static void finding(ArrayNode findings, String severity, String message) {
    var finding = findings.addObject();
    finding.put("severity", severity);
    finding.put("message", message);
  }
}
