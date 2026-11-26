package com.axonivy.utils.rule.engine.runtime.dmn;

import java.util.List;

import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;

import com.axonivy.utils.rule.engine.runtime.api.model.ModelInfo;

public record CompiledModel(
    String model,
    int version,
    String dmnName,
    String dmnNamespace,
    List<ModelInfo.Decision> decisions,
    List<ModelInfo.Input> inputs,
    DMNRuntime runtime,
    DMNModel dmnModel) {

  public boolean hasDecision(String name) {
    return decisions.stream().anyMatch(decision -> decision.name().equals(name));
  }
}
