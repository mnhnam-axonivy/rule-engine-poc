package com.axonivy.utils.rule.engine.runtime.dmn;

import java.util.List;
import java.util.stream.Collectors;

import com.axonivy.utils.rule.engine.runtime.api.model.Message;

public class InvalidModelException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<Message> messages;

  public InvalidModelException(List<Message> messages) {
    super(summary(messages));
    this.messages = List.copyOf(messages);
  }

  public List<Message> messages() {
    return messages;
  }

  private static String summary(List<Message> messages) {
    var errors = messages.stream().filter(Message::isError).toList();
    if (errors.isEmpty()) {
      return "The DMN model is invalid.";
    }
    var first = errors.stream().limit(3).map(Message::message).collect(Collectors.joining(" | "));
    var more = errors.size() > 3 ? " (and " + (errors.size() - 3) + " more)" : "";
    return "The DMN model is invalid: " + first + more;
  }
}
