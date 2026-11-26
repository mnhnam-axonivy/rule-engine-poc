package com.axonivy.utils.rule.engine.runtime.api.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "ValidationResponse")
public record ValidationResult(boolean valid, List<Message> messages) {

  public List<Message> messages() {
    return messages == null ? List.of() : messages;
  }
}
