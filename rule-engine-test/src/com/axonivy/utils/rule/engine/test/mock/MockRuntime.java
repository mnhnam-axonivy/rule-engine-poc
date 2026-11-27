package com.axonivy.utils.rule.engine.test.mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.axonivy.utils.rule.engine.api.LoadedVersion;
import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.environment.Ivy;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Like the runtime, it holds the loaded versions in memory only and answers MODEL_NOT_FOUND or VERSION_NOT_FOUND for
 * a version it was not given.
 */
@Path(MockRuntime.PATH)
@PermitAll // the connector calls without an Ivy session
@Hidden // not part of the API of this application
@SuppressWarnings("all")
public class MockRuntime {

  public static final String PATH = "ruleMock";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A request the mock received. */
  public record Call(String operation, String model, JsonNode body, String apiKey) {}

  private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  private static final Map<String, String> LOADED = new ConcurrentHashMap<>();
  private static volatile Function<Call, Response> evaluate;
  private static volatile Function<Call, Response> validate;
  private static volatile String editorScript;

  /** @return the URL of the mock, to be used as variable RuleEngine.Url */
  public static String url() {
    return Ivy.rest().client("mockRuntime").getUri().toASCIIString();
  }

  public static void reset() {
    CALLS.clear();
    LOADED.clear();
    editorScript = null;
    evaluate = call -> results(call.body().get("version").asInt(), "{}");
    validate = call -> json(200, "{\"valid\":true,\"messages\":[]}");
  }

  public static void restart() {
    LOADED.clear();
  }

  public static String loaded(String model, int version) {
    return LOADED.get(key(model, version));
  }

  /** The script of the DMN editor that the runtime serves at /editor/lib/dmn-editor.js; null = none. */
  public static void editorScript(String script) {
    editorScript = script;
  }

  public static void onEvaluate(Function<Call, Response> handler) {
    evaluate = handler;
  }

  public static void onValidate(Function<Call, Response> handler) {
    validate = handler;
  }

  /** An error answer of the runtime with findings of the DMN engine. */
  public static Response error(int status, String code, String message, String... findings) {
    var details = new java.util.ArrayList<String>();
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

  /** An evaluation answer of the runtime with the given results as JSON object. */
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
  public Response loaded(@Context HttpHeaders headers) {
    CALLS.add(new Call("loaded", null, null, headers.getHeaderString("X-API-Key")));
    var loaded = LOADED.entrySet().stream()
        .map(entry -> describe(entry.getKey().split("#")[0], Integer.parseInt(entry.getKey().split("#")[1]),
            entry.getValue()))
        .toList();
    return json(200, MAPPER.writeValueAsString(loaded));
  }

  @PUT
  @Path("api/models/{model}/versions/{version}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response load(@PathParam("model") String model, @PathParam("version") int version, String body,
      @Context HttpHeaders headers) {
    var request = (ObjectNode) parse(body);
    request.put("version", version);
    CALLS.add(new Call("load", model, request, headers.getHeaderString("X-API-Key")));
    String content = request.get("content").asString();
    try {
      var loaded = describe(model, version, content);
      LOADED.put(key(model, version), content);
      return json(200, MAPPER.writeValueAsString(loaded));
    } catch (IllegalArgumentException ex) {
      return error(400, "INVALID_MODEL", "The DMN model is invalid: " + ex.getMessage());
    }
  }

  @DELETE
  @Path("api/models/{model}")
  public Response unload(@PathParam("model") String model, @Context HttpHeaders headers) {
    CALLS.add(new Call("unload", model, null, headers.getHeaderString("X-API-Key")));
    LOADED.keySet().removeIf(key -> key.startsWith(model + "#"));
    return Response.noContent().build();
  }

  @POST
  @Path("api/models/{model}/versions/{version}/evaluate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response evaluate(@PathParam("model") String model, @PathParam("version") int version, String body,
      @Context HttpHeaders headers) {
    var request = (ObjectNode) parse(body);
    request.put("version", version);
    var call = new Call("evaluate", model, request, headers.getHeaderString("X-API-Key"));
    CALLS.add(call);
    if (!LOADED.containsKey(key(model, version))) {
      boolean modelKnown = LOADED.keySet().stream().anyMatch(key -> key.startsWith(model + "#"));
      return modelKnown
          ? error(404, "VERSION_NOT_FOUND", "Version " + version + " of model '" + model + "' is not loaded.")
          : error(404, "MODEL_NOT_FOUND", "Model '" + model + "' is not loaded.");
    }
    return evaluate.apply(call);
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

  private static LoadedVersion describe(String model, int version, String content) {
    var info = DmnModelInfo.parse(content);
    var decisions = info.decisions().stream()
        .map(decision -> new LoadedVersion.Decision(decision.name(), decision.typeRef(), decision.requiredInputs()))
        .toList();
    var inputs = info.inputs().stream()
        .map(input -> new LoadedVersion.Input(input.name(), input.typeRef(), false))
        .toList();
    return new LoadedVersion(model, version, ProjectDmnFiles.hash(content), info.name(), info.namespace(), decisions,
        inputs);
  }

  private static String key(String model, int version) {
    return model + "#" + version;
  }

  private static JsonNode parse(String json) {
    return MAPPER.readTree(json);
  }
}
