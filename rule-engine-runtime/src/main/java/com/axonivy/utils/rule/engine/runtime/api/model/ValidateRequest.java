package com.axonivy.utils.rule.engine.runtime.api.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A DMN file to check without storing it.
 */
@Schema(name = "ValidateRequest")
public record ValidateRequest(@Schema(description = "The DMN file as XML.", required = true) String content) {}
