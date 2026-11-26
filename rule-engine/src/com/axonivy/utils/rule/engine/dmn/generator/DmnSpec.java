package com.axonivy.utils.rule.engine.dmn.generator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public record DmnSpec(String modelName, List<DmnField> inputs, List<DmnField> outputs, String description) {

  public DmnSpec(String modelName, List<DmnField> inputs, List<DmnField> outputs) {
    this(modelName, inputs, outputs, null);
  }

  public DmnSpec {
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("The model needs a name");
    }
    modelName = modelName.trim();
    inputs = normalize(inputs, "input");
    outputs = normalize(outputs, "output");
    if (inputs.isEmpty()) {
      throw new IllegalArgumentException("Select at least one input field for model '" + modelName + "'");
    }
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("Select at least one output field for model '" + modelName + "'");
    }
    description = description == null || description.isBlank() ? null : description.trim();
  }

  /**
   * With a single output the decision is named like the output field, so its result is mapped by name. With several
   * outputs the decision is named like the model and returns a structure with one entry per output field.
   */
  public String decisionName() {
    String name = outputs.size() == 1
        ? outputs.get(0).name()
        : FeelNames.toFeelName(decapitalize(modelName));
    boolean clashesWithInput = inputs.stream().anyMatch(input -> input.name().equals(name));
    return clashesWithInput ? name + "Result" : name;
  }

  private static List<DmnField> normalize(List<DmnField> fields, String kind) {
    var normalized = new ArrayList<DmnField>();
    var names = new HashSet<String>();
    for (DmnField field : fields == null ? List.<DmnField>of() : fields) {
      String name = FeelNames.toFeelName(field.name());
      if (!names.add(name)) {
        throw new IllegalArgumentException("Duplicate " + kind + " field name '" + name
            + "'. Field names must be unique, see FeelNames.uniqueNames()");
      }
      normalized.add(new DmnField(name, field.type()));
    }
    return List.copyOf(normalized);
  }

  private static String decapitalize(String name) {
    return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
  }
}
