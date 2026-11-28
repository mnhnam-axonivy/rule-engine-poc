package com.axonivy.utils.rule.engine.api;

import java.util.List;

/**
 * Whether the DMN engine accepts a DMN file, and what it found.
 */
public record ValidationResult(boolean valid, List<Message> messages) {

  public List<Message> messages() {
    return messages == null ? List.of() : messages;
  }
}
