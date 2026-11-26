package com.axonivy.utils.rule.engine.runtime.api.model;

import java.time.OffsetDateTime;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A DMN model to store as a new version.
 */
@Schema(name = "RegisterVersionRequest", description = "A DMN model to store as a new version.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterVersionRequest(
    @Schema(description = "The DMN file as XML.", required = true) String content,
    @Schema(description = "Who provides this version.") String author,
    @Schema(description = "What changed.") String description,
    @Schema(description = "Origin of the version. Defaults to API.") VersionSource source,
    @Schema(description = "true = go live now, false = keep as draft. "
        + "Not set = the first version of a model and project deployments go live, "
        + "unless the active version was activated from the Portal or the API.") Boolean activate,
    @Schema(description = "Go live at this time instead of now.") OffsetDateTime effectiveDate) {}
