package com.axonivy.utils.rule.engine.client;

import java.util.List;
import java.util.Optional;

import com.axonivy.utils.rule.engine.api.Message;

/**
 * The DMN engine evaluates every decision of the model, whatever decision is asked for.
 *
 * @param messages the findings of all decisions; a decision that was not asked for may fail, e.g. for want of an
 *          input, without harming the one that was
 */
public record DmnResult(List<Decision> decisions, List<Message> messages) {

  public record Decision(String name, Object result, String status, List<Message> messages) {

    public static final String SUCCEEDED = "SUCCEEDED";

    public boolean succeeded() {
      return SUCCEEDED.equals(status) && messages.stream().noneMatch(Message::isError);
    }
  }

  public Optional<Decision> decision(String name) {
    return decisions.stream().filter(decision -> decision.name().equals(name)).findFirst();
  }

  public List<String> decisionNames() {
    return decisions.stream().map(Decision::name).toList();
  }
}
