package com.axonivy.utils.rule.engine.api;

import java.util.List;

public record ValidationResult(boolean valid, List<Message> messages) {

  public List<Message> messages() {
    return messages == null ? List.of() : messages;
  }
}
