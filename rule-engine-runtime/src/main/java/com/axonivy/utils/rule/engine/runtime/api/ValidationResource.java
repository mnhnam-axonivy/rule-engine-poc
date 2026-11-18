package com.axonivy.utils.rule.engine.runtime.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.api.ValidateRequest;
import com.axonivy.utils.rule.engine.api.ValidationResult;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnCompiler;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/validate")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Validation", description = "Check a DMN file without storing it.")
public class ValidationResource {

  @Inject
  DmnCompiler compiler;

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Validates a DMN file against the DMN schema, the model rules and the compiler.")
  public ValidationResult validate(ValidateRequest request) {
    return validateContent(request == null ? null : request.content());
  }

  @POST
  @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.TEXT_PLAIN})
  @Operation(summary = "Validates a DMN file (raw XML body).")
  public ValidationResult validateXml(String content) {
    return validateContent(content);
  }

  private ValidationResult validateContent(String content) {
    var messages = compiler.validate(content);
    boolean valid = messages.stream().noneMatch(Message::isError);
    return new ValidationResult(valid, messages);
  }
}
