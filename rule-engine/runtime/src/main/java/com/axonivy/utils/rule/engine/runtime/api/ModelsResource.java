package com.axonivy.utils.rule.engine.runtime.api;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import com.axonivy.utils.rule.engine.runtime.api.model.ActivateRequest;
import com.axonivy.utils.rule.engine.runtime.api.model.EvaluateRequest;
import com.axonivy.utils.rule.engine.runtime.api.model.EvaluateResponse;
import com.axonivy.utils.rule.engine.runtime.api.model.ModelInfo;
import com.axonivy.utils.rule.engine.runtime.api.model.ModelVersion;
import com.axonivy.utils.rule.engine.runtime.api.model.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.runtime.api.model.VersionSource;
import com.axonivy.utils.rule.engine.runtime.service.ModelRegistry;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException;
import com.axonivy.utils.rule.engine.runtime.service.VersionService;
import com.axonivy.utils.rule.engine.runtime.service.VersionService.EvaluationOutcome;
import com.axonivy.utils.rule.engine.runtime.service.VersionService.RegisterCommand;
import com.axonivy.utils.rule.engine.runtime.store.ModelVersionEntity;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/models")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Models", description = "Load DMN models, manage their versions and evaluate decisions.")
public class ModelsResource {

  @Inject
  VersionService service;

  @Inject
  ModelRegistry registry;

  @GET
  @Operation(summary = "Lists all models.")
  public List<ModelInfo> models() {
    return service.modelNames().stream().map(this::describe).toList();
  }

  @GET
  @Path("{model}")
  @Operation(summary = "Describes a model: its versions and the decisions and inputs of the active version.")
  public ModelInfo model(@PathParam("model") String model) {
    service.versions(model); // fails with MODEL_NOT_FOUND
    return describe(model);
  }

  @DELETE
  @Path("{model}")
  @Operation(summary = "Removes a model with all its versions.")
  public Response delete(@PathParam("model") String model) {
    service.delete(model);
    return Response.noContent().build();
  }

  @GET
  @Path("{model}/versions")
  @Operation(summary = "Lists the versions of a model, newest first.")
  public List<ModelVersion> versions(@PathParam("model") String model) {
    return service.versions(model).stream().map(Responses::version).toList();
  }

  @POST
  @Path("{model}/versions")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Loads a DMN model as a new version.",
      description = "Content that is already stored for the model does not create another version. "
          + "An invalid DMN file is rejected with INVALID_MODEL and the findings of the DMN engine.")
  public Response register(@PathParam("model") String model, RegisterVersionRequest request) {
    if (request == null || request.content() == null || request.content().isBlank()) {
      throw RuleEngineException.invalidRequest("The request needs the DMN file in 'content'.");
    }
    return register(model, new RegisterCommand(request.content(), request.author(), request.description(),
        request.source(), request.activate(), request.effectiveDate()));
  }

  @POST
  @Path("{model}/versions")
  @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.TEXT_PLAIN})
  @Operation(summary = "Loads a DMN file as a new version (raw XML body).")
  public Response registerXml(@PathParam("model") String model, String content,
      @QueryParam("author") String author,
      @QueryParam("description") String description,
      @QueryParam("source") VersionSource source,
      @QueryParam("activate") Boolean activate,
      @Parameter(description = "ISO-8601 date and time with offset, e.g. 2027-01-01T00:00:00+01:00")
      @QueryParam("effectiveDate") String effectiveDate) {
    if (content == null || content.isBlank()) {
      throw RuleEngineException.invalidRequest("The request body needs the DMN file.");
    }
    return register(model, new RegisterCommand(content, author, description, source, activate,
        parseDate(effectiveDate)));
  }

  private Response register(String model, RegisterCommand command) {
    var registration = service.register(model, command);
    var version = service.version(model, registration.version().version);
    var body = Responses.version(version, registration.created());
    if (registration.created()) {
      return Response.created(URI.create("/api/models/" + model + "/versions/" + version.version))
          .entity(body)
          .build();
    }
    return Response.ok(body).build();
  }

  @GET
  @Path("{model}/versions/{version}")
  @Operation(summary = "Describes one version.")
  public ModelVersion version(@PathParam("model") String model, @PathParam("version") int version) {
    return Responses.version(service.version(model, version));
  }

  @GET
  @Path("{model}/versions/{version}/content")
  @Produces(MediaType.APPLICATION_XML)
  @Operation(summary = "Exports the DMN file of a version, e.g. to bring it back into the Ivy project.")
  public String content(@PathParam("model") String model, @PathParam("version") int version) {
    return service.version(model, version).content;
  }

  @POST
  @Path("{model}/versions/{version}/approve")
  @Operation(summary = "Releases a draft for activation.")
  public ModelVersion approve(@PathParam("model") String model, @PathParam("version") int version) {
    return Responses.version(service.approve(model, version));
  }

  @POST
  @Path("{model}/versions/{version}/activate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Makes a version the active one, now or at an effective date.",
      description = "The version that was active becomes retired. No restart, running evaluations are not affected.")
  public ModelVersion activate(@PathParam("model") String model, @PathParam("version") int version,
      ActivateRequest request) {
    String user = request == null ? null : request.user();
    OffsetDateTime effectiveDate = request == null ? null : request.effectiveDate();
    service.activate(model, version, user, effectiveDate);
    return Responses.version(service.version(model, version));
  }

  @POST
  @Path("{model}/versions/{version}/retire")
  @Operation(summary = "Withdraws a version that is not active.")
  public ModelVersion retire(@PathParam("model") String model, @PathParam("version") int version) {
    return Responses.version(service.retire(model, version));
  }

  @POST
  @Path("{model}/rollback")
  @Operation(summary = "Activates the version that was active before the current one.")
  public ModelVersion rollback(@PathParam("model") String model, @QueryParam("user") String user) {
    ModelVersionEntity previous = service.rollback(model, user);
    return Responses.version(service.version(model, previous.version));
  }

  @POST
  @Path("{model}/evaluate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Evaluates the active version of a model.")
  public EvaluateResponse evaluate(@PathParam("model") String model, EvaluateRequest request) {
    var outcome = service.evaluate(model, decision(request), input(request));
    return toResponse(outcome);
  }

  @POST
  @Path("{model}/versions/{version}/evaluate")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Evaluates a specific version, e.g. to test a draft before it goes live.")
  public EvaluateResponse evaluateVersion(@PathParam("model") String model, @PathParam("version") int version,
      EvaluateRequest request) {
    var outcome = service.evaluate(model, version, decision(request), input(request));
    return toResponse(outcome);
  }

  private static String decision(EvaluateRequest request) {
    return request == null ? null : request.decision();
  }

  private static java.util.Map<String, Object> input(EvaluateRequest request) {
    return request == null || request.input() == null ? java.util.Map.of() : request.input();
  }

  private static EvaluateResponse toResponse(EvaluationOutcome outcome) {
    var results = outcome.evaluation().results();
    Object result = outcome.decision() == null ? null : results.get(outcome.decision());
    var messages = outcome.evaluation().messages();
    return new EvaluateResponse(outcome.model().model(), outcome.model().version(), outcome.decision(),
        result, results, messages.isEmpty() ? null : messages);
  }

  private ModelInfo describe(String model) {
    var versions = service.versions(model);
    var latest = versions.get(0);
    var active = registry.active(model);
    return new ModelInfo(model,
        active.map(compiled -> compiled.version()).orElse(null),
        latest.version,
        versions.size(),
        active.map(compiled -> compiled.dmnName()).orElse(latest.dmnName),
        active.map(compiled -> compiled.dmnNamespace()).orElse(latest.dmnNamespace),
        active.map(compiled -> compiled.decisions()).orElse(null),
        active.map(compiled -> compiled.inputs()).orElse(null));
  }

  private static OffsetDateTime parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.trim());
    } catch (RuntimeException ex) {
      throw RuleEngineException.invalidRequest("Invalid effectiveDate '" + value
          + "'. Expected ISO-8601 with offset, e.g. 2027-01-01T00:00:00+01:00.");
    }
  }
}
