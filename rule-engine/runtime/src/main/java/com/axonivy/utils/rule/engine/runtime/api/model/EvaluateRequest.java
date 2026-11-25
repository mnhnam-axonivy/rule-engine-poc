package com.axonivy.utils.rule.engine.runtime.api.model;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Evaluates one decision, or all decisions, of a model with the given inputs.
 */
@Schema(name = "EvaluateRequest")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluateRequest(
    @Schema(description = "Name of the decision to evaluate. Not set = all decisions of the model.") String decision,
    @Schema(description = "Input values by the name of the DMN input.") Map<String, Object> input) {}
