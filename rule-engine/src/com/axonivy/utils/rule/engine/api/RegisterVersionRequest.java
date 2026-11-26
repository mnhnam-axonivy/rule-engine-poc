package com.axonivy.utils.rule.engine.api;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterVersionRequest(
    String content,
    String author,
    String description,
    VersionSource source,
    Boolean activate,
    OffsetDateTime effectiveDate) {

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
