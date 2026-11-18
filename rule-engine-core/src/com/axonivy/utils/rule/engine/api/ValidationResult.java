package com.axonivy.utils.rule.engine.api;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Whether the runtime accepts a DMN file, and what its engine found.
 */
@Schema(name = "ValidationResponse")
public record ValidationResult(boolean valid, List<Message> messages) {

  public List<Message> messages() {
    return messages == null ? List.of() : messages;
  }
}
