package com.axonivy.utils.rule.engine.program.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.axonivy.utils.rule.engine.BusinessRuleTask;
import com.axonivy.utils.rule.engine.client.internal.JsonConverter;
import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.java.project.ProjectClassLoader;
import tools.jackson.databind.JsonNode;

/**
 * "New DMN..." at design time.
 * <p>The inscription API offers no button and no change event yet. So the selection of the dropdown entry is
 * picked up when the inscription mask is opened the next time: every Business Rule Task of the project that
 * asks for a new DMN gets its file, and its configuration switches to the generated decision.
 * Without a Designer the file is generated when the element is executed for the first time.</p>
 */
public final class DesignTimeCreation {

  private static final String PROCESS_EXTENSION = ".p.json";
  private static final int MAX_DEPTH = 12;

  private record Pending(String dataClass, Map<String, String> config) {}

  private DesignTimeCreation() {}

  /**
   * Generates the .dmn file of every Business Rule Task in the project that selected "New DMN...".
   * Never fails: a problem is logged and the element reports it when it is executed. An element without a name or
   * without fields is skipped: running it says what is missing.
   *
   * @return the generated files
   */
  public static List<DmnCreator.Created> createPending(Project project) {
    var created = new ArrayList<DmnCreator.Created>();
    if (project == null || project.directory() == null || !Files.isWritable(project.directory())) {
      return created;
    }
    try {
      for (Pending pending : pending(project.directory())) {
        try {
          var inputs = FieldSelection.parse(pending.config().get(Conf.INPUTS));
          var outputs = FieldSelection.parse(pending.config().get(Conf.OUTPUTS));
          String name = pending.config().get(Conf.NEW_MODEL);
          if (inputs.isEmpty() || outputs.isEmpty() || name == null || name.isBlank()) {
            continue;
          }
          Class<?> dataClass = ProjectClassLoader.of(project).loadClass(pending.dataClass());
          var file = DmnCreator.create(project, dataClass, name, inputs, outputs);
          if (file.generated()) {
            created.add(file);
          }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
          Ivy.log().warn("Rule engine: cannot create the DMN '" + pending.config().get(Conf.NEW_MODEL)
              + "': " + ex.getMessage());
        }
      }
    } catch (RuntimeException ex) {
      Ivy.log().warn("Rule engine: cannot look for Business Rule Tasks that ask for a new DMN", ex);
    }
    return created;
  }

  /**
   * Once the file of "New DMN..." exists, the element refers to its decision like to any other. Another file of the
   * same name is not selected: running the element reports it.
   *
   * @param config the configuration of a Business Rule Task
   * @return the configuration with the generated decision selected, or the unchanged configuration
   */
  public static Map<String, String> selectCreated(Project project, Map<String, String> config) {
    if (project == null || !Conf.CREATE_NEW.equals(config.get(Conf.DECISION))) {
      return config;
    }
    var inputs = FieldSelection.parse(config.get(Conf.INPUTS));
    var outputs = FieldSelection.parse(config.get(Conf.OUTPUTS));
    String name = config.get(Conf.NEW_MODEL);
    if (inputs.isEmpty() || outputs.isEmpty() || name == null || name.isBlank()) {
      return config;
    }
    String model = DmnCreator.modelName(name);
    var file = ProjectDmnFiles.find(project, model);
    if (file.isEmpty() || !DmnCreator.generatedFrom(file.get(), inputs, outputs)) {
      return config;
    }
    DmnModelInfo info = ProjectDmnFiles.load(file.get()).info();
    if (info == null || info.decisions().isEmpty()) {
      return config;
    }
    String wanted = outputs.size() == 1 ? outputs.expressionsByName().keySet().iterator().next() : model;
    String decision = info.decisions().stream()
        .map(DmnModelInfo.Decision::name)
        .filter(candidate -> candidate.equalsIgnoreCase(wanted) || candidate.equalsIgnoreCase(wanted + "Result"))
        .findFirst()
        .orElse(info.decisions().get(0).name());
    var selected = new HashMap<>(config);
    selected.put(Conf.DECISION, model + Conf.SEPARATOR + decision);
    return selected;
  }

  private static List<Pending> pending(Path projectDirectory) {
    var pending = new ArrayList<Pending>();
    try (Stream<Path> files = Files.walk(projectDirectory, MAX_DEPTH)) {
      files.filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(PROCESS_EXTENSION))
          .filter(path -> !isBuildOutput(projectDirectory.relativize(path)))
          .forEach(path -> pending.addAll(pendingOf(path)));
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot read the processes of " + projectDirectory, ex);
    }
    return pending;
  }

  private static List<Pending> pendingOf(Path process) {
    var pending = new ArrayList<Pending>();
    try {
      String json = Files.readString(process, StandardCharsets.UTF_8);
      if (!json.contains(BusinessRuleTask.class.getName()) || !json.contains(Conf.CREATE_NEW)) {
        return pending;
      }
      JsonNode root = JsonConverter.mapper().readTree(json);
      String dataClass = root.path("config").path("data").asString("");
      if (!dataClass.isBlank()) {
        collect(root.path("elements"), dataClass, pending);
      }
    } catch (IOException | RuntimeException ex) {
      Ivy.log().debug("Rule engine: skipping " + process + ": " + ex.getMessage());
    }
    return pending;
  }

  private static void collect(JsonNode elements, String dataClass, List<Pending> pending) {
    for (JsonNode element : elements) {
      JsonNode config = element.path("config");
      if (BusinessRuleTask.class.getName().equals(config.path("javaClass").asString(""))) {
        var userConfig = new HashMap<String, String>();
        config.path("userConfig").properties()
            .forEach(entry -> userConfig.put(entry.getKey(), entry.getValue().asString("")));
        if (Conf.CREATE_NEW.equals(userConfig.get(Conf.DECISION))) {
          pending.add(new Pending(dataClass, userConfig));
        }
      }
      collect(element.path("elements"), dataClass, pending); // elements of an embedded sub process
    }
  }

  private static boolean isBuildOutput(Path relative) {
    for (Path segment : relative) {
      String name = segment.toString();
      if (name.equals("target") || name.equals("classes") || name.startsWith(".")) {
        return true;
      }
    }
    return false;
  }
}
