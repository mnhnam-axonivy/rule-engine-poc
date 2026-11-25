package com.axonivy.utils.rule.engine.api;

import java.util.List;

/**
 * Whether the runtime accepts a DMN file, and what its engine found.
 */
public record ValidationResult(boolean valid, List<Message> messages) {

  public List<Message> messages() {
    return messages == null ? List.of() : messages;
  }
}
