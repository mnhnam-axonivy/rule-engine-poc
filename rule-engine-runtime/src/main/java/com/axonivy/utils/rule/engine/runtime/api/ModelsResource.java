package com.axonivy.utils.rule.engine.runtime.api;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import com.axonivy.utils.rule.engine.runtime.api.model.EvaluateRequest;
import com.axonivy.utils.rule.engine.runtime.api.model.EvaluateResponse;
import com.axonivy.utils.rule.engine.runtime.api.model.LoadRequest;
import com.axonivy.utils.rule.engine.runtime.api.model.LoadedVersion;
import com.axonivy.utils.rule.engine.runtime.service.DecisionService;
import com.axonivy.utils.rule.engine.runtime.service.DecisionService.EvaluationOutcome;
import com.axonivy.utils.rule.engine.runtime.service.ModelRegistry;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/models")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Models", description = "Load versions of DMN models into memory and evaluate their decisions. "
    + "The versions are stored by the Ivy application: it loads a version whenever the runtime answers that it "
    + "does not have it.")
public class ModelsResource {

  private final ModelRegistry registry;
  private final DecisionService decisions;

  ModelsResource(ModelRegistry registry, DecisionService decisions) {
    this.registry = registry;
    this.decisions = decisions;
  }

  @GET
  @Operation(summary = "Lists the versions in memory.")
  public List<LoadedVersion> loaded() {
    return registry.loaded().stream().map(ModelsResource::describe).toList();
  }

  @PUT
  @Path("{model}/versions/{version}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Loads a version into memory, ready to evaluate.",
      description = "Replaces what the runtime holds as this version. An invalid DMN file is rejected with "
          + "INVALID_MODEL and the findings of the DMN engine.")
  public LoadedVersion load(@PathParam("model") String model, @PathParam("version") int version,
      LoadRequest request) {
    return describe(registry.load(model, version, request == null ? null : request.content()));
  }

  @PUT
  @Path("{model}/versions/{version}")
  @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.TEXT_PLAIN})
  @Operation(summary = "Loads a version into memory (raw XML body).")
  public LoadedVersion loadXml(@PathParam("model") String model, @PathParam("version") int version,
      String content) {
    return describe(registry.load(model, version, content));
  }

  @DELETE
  @Path("{model}")
  @Operation(summary = "Removes all versions of a model from memory.")
  public Response unload(@PathParam("model") String model) {
    registry.unload(model);
    return Response.noContent().build();
  }

  @POST
  @Path("{model}/versions/{version}/evaluate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Evaluates a loaded version.",
      description = "MODEL_NOT_FOUND or VERSION_NOT_FOUND if the version is not in memory: load it, then try again.")
  public EvaluateResponse evaluate(@PathParam("model") String model, @PathParam("version") int version,
      EvaluateRequest request) {
    String decision = request == null ? null : request.decision();
    Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
    return toResponse(decisions.evaluate(model, version, decision, input));
  }

  private static EvaluateResponse toResponse(EvaluationOutcome outcome) {
    var results = outcome.evaluation().results();
    Object result = outcome.decision() == null ? null : results.get(outcome.decision());
    var messages = outcome.evaluation().messages();
    return new EvaluateResponse(outcome.model().model(), outcome.model().version(), outcome.decision(),
        result, results, messages.isEmpty() ? null : messages);
  }

  private static LoadedVersion describe(ModelRegistry.Loaded loaded) {
    var compiled = loaded.model();
    return new LoadedVersion(compiled.model(), compiled.version(), loaded.contentHash(), compiled.dmnName(),
        compiled.dmnNamespace(), compiled.decisions(), compiled.inputs());
  }
}
