package com.axonivy.utils.rule.engine.runtime.dmn;

import org.kie.api.builder.Message.Level;
import org.kie.dmn.api.core.DMNMessage;

import com.axonivy.utils.rule.engine.runtime.api.model.Message;

/**
 * Readable findings of the DMN compiler, validator or evaluation of Apache KIE.
 */
public final class Messages {

  private Messages() {}

  public static Message of(DMNMessage message) {
    return new Message(
        severity(message.getLevel()),
        readable(message.getText()),
        message.getMessageType() == null ? null : message.getMessageType().name(),
        message.getSourceId());
  }

  /** The level of a finding as the API names it: ERROR, WARN or INFO. */
  private static String severity(Level level) {
    if (level == null) {
      return Message.ERROR;
    }
    return switch (level) {
      case ERROR -> Message.ERROR;
      case WARNING -> Message.WARN;
      case INFO -> Message.INFO;
    };
  }

  /**
   * The engine prefixes its messages with technical noise. Keep what helps a rule author.
   */
  private static String readable(String text) {
    if (text == null) {
      return "";
    }
    String readable = text.strip();
    if (readable.startsWith("DMN: ")) {
      readable = readable.substring("DMN: ".length());
    }
    return readable.replace("of DMN file: null - ", "of the DMN file: ");
  }
}
