package com.axonivy.utils.rule.engine.runtime.api.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "LoadRequest")
public record LoadRequest(@Schema(description = "The DMN file as XML.", required = true) String content) {}
