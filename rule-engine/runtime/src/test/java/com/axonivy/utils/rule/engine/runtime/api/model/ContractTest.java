package com.axonivy.utils.rule.engine.runtime.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The runtime and the Ivy connector have records of their own for the same JSON. They have to describe the same
 * fields: whoever changes one side changes the other.
 */
class ContractTest {

  private static final String RUNTIME = "com.axonivy.utils.rule.engine.runtime.api.model.";
  private static final String CONNECTOR = "com.axonivy.utils.rule.engine.api.";

  @ParameterizedTest
  @ValueSource(strings = {"ActivateRequest", "ErrorResponse", "EvaluateRequest", "EvaluateResponse", "Message", "ModelInfo",
      "ModelInfo$Decision", "ModelInfo$Input", "ModelVersion", "RegisterVersionRequest", "ValidationResult"})
  void recordsHaveTheSameFields(String name) throws ClassNotFoundException {
    assertThat(fields(CONNECTOR, name)).as("fields of " + name + " in the connector").isEqualTo(fields(RUNTIME, name));
  }

  @ParameterizedTest
  @ValueSource(strings = {"VersionSource", "VersionStatus"})
  void enumsHaveTheSameConstants(String name) throws ClassNotFoundException {
    assertThat(constants(CONNECTOR, name)).as("constants of " + name + " in the connector").isEqualTo(constants(RUNTIME, name));
  }

  private static List<String> fields(String packageName, String name) throws ClassNotFoundException {
    return Arrays.stream(Class.forName(packageName + name).getRecordComponents())
        .map(field -> field.getName() + ": " + field.getGenericType().getTypeName().replace(packageName, ""))
        .toList();
  }

  private static List<String> constants(String packageName, String name) throws ClassNotFoundException {
    return Arrays.stream(Class.forName(packageName + name).getEnumConstants()).map(Object::toString).toList();
  }
}
