package com.axonivy.utils.rule.engine.test.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import ch.ivyteam.ivy.environment.Ivy;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Path(MockRuntime.PATH)
@PermitAll // the connector calls without an Ivy session
@Hidden
@SuppressWarnings("all")
public class MockRuntime {

  public static final String PATH = "ruleMock";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public record Call(String operation, String model, JsonNode body, String apiKey) {}

  private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  private static final Map<String, String> MODELS = new ConcurrentHashMap<>();
  private static final Map<String, String> VERSIONS = new ConcurrentHashMap<>();
  private static final Map<String, String> CONTENTS = new ConcurrentHashMap<>();
  private static final Map<String, String> SOURCES = new ConcurrentHashMap<>();
  private static volatile Function<Call, Response> evaluate;
  private static volatile Function<Call, Response> register;
  private static volatile Function<Call, Response> activate;
  private static volatile Function<Call, Response> rollback;
  private static volatile Function<Call, Response> validate;
  private static volatile String editorScript;

  public static String url() {
    return Ivy.rest().client("mockRuntime").getUri().toASCIIString();
  }

  public static void reset() {
    CALLS.clear();
    MODELS.clear();
    VERSIONS.clear();
    CONTENTS.clear();
    SOURCES.clear();
    editorScript = null;
    evaluate = call -> error(404, "MODEL_NOT_FOUND", "Model '" + call.model() + "' is not loaded.");
    register = call -> json(201, "{\"model\":\"" + call.model()
        + "\",\"version\":1,\"status\":\"ACTIVE\",\"source\":\"PROJECT\",\"created\":true}");
    activate = call -> json(200, "{\"model\":\"" + call.model() + "\",\"version\":" + call.body().get("version")
        + ",\"status\":\"ACTIVE\",\"source\":\"PORTAL\",\"activatedBy\":" + call.body().get("user") + "}");
    rollback = call -> error(409, "INVALID_STATE", "Model '" + call.model() + "' has no version to roll back to.");
    validate = call -> json(200, "{\"valid\":true,\"messages\":[]}");
  }

  public static void model(String name, String decision, String... inputs) {
    var inputNames = new ArrayList<String>();
    var inputList = new ArrayList<String>();
    for (String input : inputs) {
      inputNames.add("\"" + input + "\"");
      inputList.add("{\"name\":\"" + input + "\",\"type\":\"Any\",\"collection\":false}");
    }
    MODELS.put(name, "{\"name\":\"" + name + "\",\"activeVersion\":1,\"latestVersion\":1,\"versionCount\":1,"
        + "\"decisions\":[{\"name\":\"" + decision + "\",\"requiredInputs\":[" + String.join(",", inputNames)
        + "]}],\"inputs\":[" + String.join(",", inputList) + "]}");
  }

  public static void activeVersion(String model, int version) {
    MODELS.computeIfPresent(model, (name, json) -> json.replaceFirst("\"activeVersion\":\\d+","\"activeVersion\":" + version));
  }

  public static void activeSource(String model, String source) {
    SOURCES.put(model, source);
  }

  public static void versions(String model, String versionsJson) {
    VERSIONS.put(model, versionsJson);
  }

  public static void content(String model, int version, String dmn) {
    CONTENTS.put(model + ":" + version, dmn);
  }

  public static void editorScript(String script) {
    editorScript = script;
  }

  public static void onEvaluate(Function<Call, Response> handler) {
    evaluate = handler;
  }

  public static void onRegister(Function<Call, Response> handler) {
    register = handler;
  }

  public static void onActivate(Function<Call, Response> handler) {
    activate = handler;
  }

  public static void onRollback(Function<Call, Response> handler) {
    rollback = handler;
  }

  public static void onValidate(Function<Call, Response> handler) {
    validate = handler;
  }

  public static Response error(int status, String code, String message, String... findings) {
    var details = new ArrayList<String>();
    for (String finding : findings) {
      details.add("{\"severity\":\"ERROR\",\"message\":\"" + finding.replace("\"", "'") + "\"}");
    }
    return json(status, "{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'")
        + "\",\"details\":[" + String.join(",", details) + "]}");
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

  public static Response error(int status, String code, String message) {
    return json(status, "{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
  }

  public static Response results(int version, String resultsJson) {
    return json(200, "{\"model\":\"mock\",\"version\":" + version + ",\"results\":" + resultsJson + "}");
  }

  @GET
  @Path("q/health/ready")
  @Produces(MediaType.APPLICATION_JSON)
  public Response ready() {
    return json(200, "{\"status\":\"UP\"}");
  }

  @GET
  @Path("editor/lib/dmn-editor.js")
  public Response editorScriptFile() {
    CALLS.add(new Call("editorScript", null, null, null));
    String script = editorScript;
    return script == null
        ? Response.status(404).build()
        : Response.ok(script, "application/javascript").build();
  }

  @GET
  @Path("api/models")
  @Produces(MediaType.APPLICATION_JSON)
  public Response models(@Context HttpHeaders headers) {
    CALLS.add(new Call("models", null, null, headers.getHeaderString("X-API-Key")));
    return json(200, "[" + String.join(",", MODELS.values()) + "]");
  }

  @GET
  @Path("api/models/{model}/versions")
  @Produces(MediaType.APPLICATION_JSON)
  public Response versions(@PathParam("model") String model, @Context HttpHeaders headers) {
    CALLS.add(new Call("versions", model, null, headers.getHeaderString("X-API-Key")));
    String versions = VERSIONS.get(model);
    return versions == null
        ? error(404, "MODEL_NOT_FOUND", "Model '" + model + "' is not loaded.")
        : json(200, versions);
  }

  @GET
  @Path("api/models/{model}/versions/{version}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response version(@PathParam("model") String model, @PathParam("version") int version,
      @Context HttpHeaders headers) {
    CALLS.add(new Call("version", model, null, headers.getHeaderString("X-API-Key")));
    if (!MODELS.containsKey(model)) {
      return error(404, "MODEL_NOT_FOUND", "Model '" + model + "' is not loaded.");
    }
    return json(200, "{\"model\":\"" + model + "\",\"version\":" + version + ",\"status\":\"ACTIVE\",\"source\":\""
        + SOURCES.getOrDefault(model, "PROJECT") + "\"}");
  }

  @GET
  @Path("api/models/{model}/versions/{version}/content")
  @Produces(MediaType.APPLICATION_XML)
  public Response content(@PathParam("model") String model, @PathParam("version") int version,
      @Context HttpHeaders headers) {
    CALLS.add(new Call("content", model, null, headers.getHeaderString("X-API-Key")));
    String dmn = CONTENTS.get(model + ":" + version);
    return dmn == null
        ? error(404, "VERSION_NOT_FOUND", "Version " + version + " of '" + model + "' does not exist.")
        : Response.ok(dmn, MediaType.APPLICATION_XML).build();
  }

  @POST
  @Path("api/models/{model}/versions/{version}/activate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response activate(@PathParam("model") String model, @PathParam("version") int version, String body,
      @Context HttpHeaders headers) {
    var request = (ObjectNode) parse(body == null || body.isBlank() ? "{}" : body);
    request.put("version", version);
    var call = new Call("activate", model, request, headers.getHeaderString("X-API-Key"));
    CALLS.add(call);
    return activate.apply(call);
  }

  @POST
  @Path("api/models/{model}/versions/{version}/approve")
  @Produces(MediaType.APPLICATION_JSON)
  public Response approve(@PathParam("model") String model, @PathParam("version") int version,
      @Context HttpHeaders headers) {
    CALLS.add(new Call("approve", model, MAPPER.createObjectNode().put("version", version),
        headers.getHeaderString("X-API-Key")));
    return json(200, "{\"model\":\"" + model + "\",\"version\":" + version + ",\"status\":\"APPROVED\"}");
  }

  @POST
  @Path("api/models/{model}/versions/{version}/retire")
  @Produces(MediaType.APPLICATION_JSON)
  public Response retire(@PathParam("model") String model, @PathParam("version") int version,
      @Context HttpHeaders headers) {
    CALLS.add(new Call("retire", model, MAPPER.createObjectNode().put("version", version),
        headers.getHeaderString("X-API-Key")));
    return json(200, "{\"model\":\"" + model + "\",\"version\":" + version + ",\"status\":\"RETIRED\"}");
  }

  @DELETE
  @Path("api/models/{model}")
  public Response delete(@PathParam("model") String model, @Context HttpHeaders headers) {
    CALLS.add(new Call("delete", model, null, headers.getHeaderString("X-API-Key")));
    MODELS.remove(model);
    return Response.noContent().build();
  }

  @POST
  @Path("api/models/{model}/versions/{version}/evaluate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response evaluateVersion(@PathParam("model") String model, @PathParam("version") int version, String body,
      @Context HttpHeaders headers) {
    var request = (ObjectNode) parse(body);
    request.put("version", version);
    var call = new Call("evaluateVersion", model, request, headers.getHeaderString("X-API-Key"));
    CALLS.add(call);
    return evaluate.apply(call);
  }

  @POST
  @Path("api/models/{model}/rollback")
  @Produces(MediaType.APPLICATION_JSON)
  public Response rollback(@PathParam("model") String model, @QueryParam("user") String user,
      @Context HttpHeaders headers) {
    var call = new Call("rollback", model, MAPPER.createObjectNode().put("user", user), headers.getHeaderString("X-API-Key"));
    CALLS.add(call);
    return rollback.apply(call);
  }

  @POST
  @Path("api/validate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response validate(String body, @Context HttpHeaders headers) {
    var call = new Call("validate", null, parse(body), headers.getHeaderString("X-API-Key"));
    CALLS.add(call);
    return validate.apply(call);
  }

  @GET
  @Path("api/models/{model}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response model(@PathParam("model") String model, @Context HttpHeaders headers) {
    CALLS.add(new Call("model", model, null, headers.getHeaderString("X-API-Key")));
    String description = MODELS.get(model);
    return description == null
        ? error(404, "MODEL_NOT_FOUND", "Model '" + model + "' is not loaded.")
        : json(200, description);
  }

  @POST
  @Path("api/models/{model}/evaluate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response evaluate(@PathParam("model") String model, String body, @Context HttpHeaders headers) {
    var call = new Call("evaluate", model, parse(body), headers.getHeaderString("X-API-Key"));
    CALLS.add(call);
    return evaluate.apply(call);
  }

  @POST
  @Path("api/models/{model}/versions")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response register(@PathParam("model") String model, String body, @Context HttpHeaders headers) {
    var call = new Call("register", model, parse(body), headers.getHeaderString("X-API-Key"));
    CALLS.add(call);
    return register.apply(call);
  }

  private static JsonNode parse(String json) {
    return MAPPER.readTree(json);
  }
}
