package com.axonivy.utils.rule.engine.program.internal;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.dmn.generator.DmnField;
import com.axonivy.utils.rule.engine.dmn.generator.DmnGenerator;
import com.axonivy.utils.rule.engine.dmn.generator.DmnSpec;
import com.axonivy.utils.rule.engine.dmn.generator.JavaTypeMapper;
import com.axonivy.utils.rule.engine.mapping.FieldLocator;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * "New DMN...": generates a .dmn file in the dmn folder of the project.
 * The names of the nodes are the names of the fields, so inputs and results are mapped without configuration.
 * The file says which fields it was generated from: a later run of the same element uses it and never overwrites
 * it, any other file of that name is an error.
 */
public final class DmnCreator {

  /** A field of the process data, e.g. <code>in.member.purchaseAmount</code>. */
  private static final Pattern FIELD_PATH = Pattern.compile("in(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

  /**
   * @param model name of the model, the file name without extension
   * @param decision name of the decision to evaluate
   * @param file the .dmn file
   * @param generated false if the file already existed and was left untouched
   */
  public record Created(String model, String decision, Path file, boolean generated) {

    public String selection() {
      return model + Conf.SEPARATOR + decision;
    }
  }

  private DmnCreator() {}

  /**
   * @param project the project that gets the file
   * @param dataClass class of the process data the field paths belong to
   * @param modelName name of the model, the file name: required
   * @param inputs selected input fields
   * @param outputs selected output fields
   * @throws IllegalArgumentException if the name is empty or taken by another file, the selection is incomplete
   *         or a field does not exist
   * @throws IllegalStateException if the project cannot be written, e.g. on a deployed engine
   */
  public static Created create(Project project, Class<?> dataClass, String modelName, FieldSelection inputs,
      FieldSelection outputs) {
    if (inputs.isEmpty() || outputs.isEmpty()) {
      throw new IllegalArgumentException("To create a new DMN, select at least one input field and one output "
          + "field, e.g. inputs 'in.member.purchaseAmount, in.member.type' and outputs 'in.member.discount'.");
    }
    String model = modelName(modelName);
    var mapper = new JavaTypeMapper();
    var spec = new DmnSpec(model, fields(dataClass, inputs, mapper), fields(dataClass, outputs, mapper),
        origin(inputs, outputs));

    var existing = ProjectDmnFiles.find(project, model);
    if (existing.isPresent()) {
      if (generatedFrom(existing.get(), inputs, outputs)) {
        // the file these fields generated before: never overwritten, it may hold the rules of the author by now
        return new Created(model, decisionOf(existing.get(), spec), existing.get().path(), false);
      }
      throw new IllegalArgumentException("A DMN named '" + model + "' exists already (" + existing.get().path().getFileName()
          + "). Give the new DMN another name in 'New DMN name'.");
    }
    readableByName(dataClass, inputs, outputs);
    Path file = project.directory().resolve(ProjectDmnFiles.DMN_FOLDER).resolve(model + ProjectDmnFiles.DMN_EXTENSION);
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, new DmnGenerator().generate(spec), StandardCharsets.UTF_8);
    } catch (IOException | UnsupportedOperationException | SecurityException ex) {
      throw new IllegalStateException("Cannot write " + file + ". A new DMN can only be created in a project "
          + "of the Designer, not in a deployed project.", ex);
    }
    ProjectDmnFiles.refresh();
    Ivy.log().info("Rule engine: generated " + file + " with decision '" + spec.decisionName()
        + "'. Open it in the DMN editor and fill in the rules.");
    return new Created(model, spec.decisionName(), file, true);
  }

  /**
   * @return the model name that {@link #create} uses for the wanted name
   * @throws IllegalArgumentException if the name is empty
   */
  public static String modelName(String wanted) {
    String name = wanted == null ? "" : wanted.trim();
    if (name.toLowerCase().endsWith(ProjectDmnFiles.DMN_EXTENSION)) {
      name = name.substring(0, name.length() - ProjectDmnFiles.DMN_EXTENSION.length());
    }
    var safe = new StringBuilder();
    for (char c : name.toCharArray()) {
      boolean valid = c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.');
      safe.append(valid ? c : '-');
    }
    String result = safe.toString().replaceAll("^[-._]+", "");
    if (result.isEmpty()) {
      throw new IllegalArgumentException("'New DMN name' is empty. Name the new DMN, e.g. member-discount.");
    }
    return result;
  }

  /**
   * @return what a generated file says about where it comes from: the fields it was generated from. Plain text,
   *         so that it reads the same however an editor writes the file.
   */
  public static String origin(FieldSelection inputs, FieldSelection outputs) {
    return "Generated by a Business Rule Task. Inputs: " + String.join(", ", inputs.expressionsByName().values())
        + ". Outputs: " + String.join(", ", outputs.expressionsByName().values()) + ".";
  }

  /** @return true if these fields generated the file: then it is theirs, not another file of the same name */
  public static boolean generatedFrom(ProjectDmnFiles.DmnFile file, FieldSelection inputs, FieldSelection outputs) {
    String content = ProjectDmnFiles.load(file).content();
    return content != null && content.contains(origin(inputs, outputs));
  }

  private static List<DmnField> fields(Class<?> dataClass, FieldSelection selection, JavaTypeMapper mapper) {
    var fields = new ArrayList<DmnField>();
    selection.expressionsByName().forEach((name, expression) -> {
      if (!FIELD_PATH.matcher(expression).matches()) {
        throw new IllegalArgumentException("'" + expression + "' is no field. List fields with their path, like "
            + "in.member.purchaseAmount: each becomes an input or an output named like its field.");
      }
      Type type = FieldLocator.typeOf(dataClass, expression)
          .orElseThrow(() -> new IllegalArgumentException("The field '" + expression + "' does not exist in "
              + dataClass.getName() + ". List fields with their path, like in.member.purchaseAmount."));
      fields.add(new DmnField(name, mapper.toDmnType(type)));
    });
    return fields;
  }

  /**
   * The Business Rule Task reads the inputs and writes the results by name, also those of a generated DMN.
   * A selection that the mapping by name would not find again is refused, before there is a file.
   */
  private static void readableByName(Class<?> dataClass, FieldSelection inputs, FieldSelection outputs) {
    for (String name : outputs.expressionsByName().keySet()) {
      if (inputs.expressionsByName().containsKey(name)) {
        throw new IllegalArgumentException("'" + name + "' is selected as input and as output. A decision cannot read "
            + "and fill the same name: choose another field for the result.");
      }
    }
    inputs.expressionsByName().forEach((name, path) -> foundByName(dataClass, name, path));
    outputs.expressionsByName().forEach((name, path) -> foundByName(dataClass, name, path));
  }

  private static void foundByName(Class<?> dataClass, String name, String path) {
    String field = path.substring(path.lastIndexOf('.') + 1);
    if (!FieldLocator.normalize(name).equals(FieldLocator.normalize(field))) {
      throw new IllegalArgumentException("Several selected fields are named '" + field + "'. The Business Rule Task "
          + "finds a field by its name, so it could only read one of them: select fields with different names.");
    }
    var found = FieldLocator.findByName(dataClass, "in", name).map(FieldLocator.Field::expression);
    if (found.isEmpty()) {
      throw new IllegalArgumentException("The Business Rule Task finds a field by its name, up to 4 levels below in, "
          + "and would not find " + path + ": select a field closer to in.");
    }
    if (!found.get().equals(path)) {
      throw new IllegalArgumentException("The Business Rule Task finds a field by its name and would use "
          + found.get() + " for '" + name + "', not " + path + ", because it comes first. Select " + found.get()
          + ", or give one of the fields another name.");
    }
  }

  private static String decisionOf(ProjectDmnFiles.DmnFile file, DmnSpec spec) {
    DmnModelInfo info = ProjectDmnFiles.load(file).info();
    if (info == null || info.decisions().isEmpty()) {
      return spec.decisionName();
    }
    return info.decision(spec.decisionName())
        .map(DmnModelInfo.Decision::name)
        .orElse(info.decisions().get(0).name());
  }
}
