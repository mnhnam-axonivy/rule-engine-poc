package com.axonivy.utils.rule.engine.api;

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
    @Schema(description = "Go live at this time instead of now.") OffsetDateTime effectiveDate) {

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private String content;
    private String author;
    private String description;
    private VersionSource source;
    private Boolean activate;
    private OffsetDateTime effectiveDate;

    public Builder content(String content) {
      this.content = content;
      return this;
    }

    public Builder author(String author) {
      this.author = author;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder source(VersionSource source) {
      this.source = source;
      return this;
    }

    public Builder activate(Boolean activate) {
      this.activate = activate;
      return this;
    }

    public Builder effectiveDate(OffsetDateTime effectiveDate) {
      this.effectiveDate = effectiveDate;
      return this;
    }

    public RegisterVersionRequest build() {
      return new RegisterVersionRequest(content, author, description, source, activate, effectiveDate);
    }
  }
}
