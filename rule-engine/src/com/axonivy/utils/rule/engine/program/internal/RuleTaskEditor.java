package com.axonivy.utils.rule.engine.program.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.process.program.ui.ProgramUiBuilder;
import ch.ivyteam.ivy.process.program.ui.select.SelectItem;

public class RuleTaskEditor {

  public static final String NEW_DMN = "New DMN...";

  public void editor(ProgramUiBuilder ui) {
    Project project = currentProject();
    DesignTimeCreation.createPending(project);

    ui.group("⚖️ Decision")
        .add(ui.multiSelect(Conf.DECISION)
            .label("DMN decision:")
            .items(decisions(project))
            .create())
        .add(ui.label(String.join("\n",
            "A decision of a .dmn file of this project. Inputs and results go to the process data fields of the "
                + "same name.",
            "",
            "Choose '" + NEW_DMN + "' to create a new DMN file."))
            .multiline()
            .create())
        .create();

    ui.group("✨ New DMN")
        .add(ui.label("Only used with '" + NEW_DMN + "': the file to create and the fields it is made of. "
            + "With an existing decision these fields are ignored.")
            .multiline()
            .create())
        .add(ui.textField(Conf.NEW_MODEL)
            .label("New DMN name:")
            .help("Required, e.g. member-discount for dmn/member-discount.dmn. A name that another .dmn file has "
                + "already is an error. The file is never overwritten.")
            .create())
        .add(ui.textField(Conf.INPUTS)
            .label("Input fields:")
            .multiline()
            .help("The fields the new decision reads, separated by comma or line break, e.g. "
                + "in.member.purchaseAmount, in.member.type. Each becomes an input named like its field.")
            .create())
        .add(ui.textField(Conf.OUTPUTS)
            .label("Output fields:")
            .multiline()
            .help("The fields the new decision fills, e.g. in.member.discount. Each becomes an output named like "
                + "its field.")
            .create())
        .create();
  }

  private static List<SelectItem> decisions(Project project) {
    var items = new ArrayList<SelectItem>();
    try {
      for (var file : ProjectDmnFiles.list(project)) {
        var dmn = ProjectDmnFiles.load(file);
        DmnModelInfo info = dmn.info();
        String location = location(project, file);
        if (info == null) {
          items.add(SelectItem.of(file.model(), file.model() + " (cannot be read)", "⚠️",
              location + " · " + dmn.parseError()));
          continue;
        }
        for (var decision : info.decisions()) {
          items.add(SelectItem.of(file.model() + Conf.SEPARATOR + decision.name(),
              file.model() + " › " + decision.name(), "📋",
              location + " · in: " + info.describeInputs(decision) + " · out: " + DmnModelInfo.describeOutputs(decision)));
        }
        if (info.decisions().size() > 1) {
          items.add(SelectItem.of(file.model(), file.model() + " › all decisions", "🗂️",
              location + " · evaluates every decision of the model · in: "
                  + DmnModelInfo.describe(info.inputNames(), info.inputTypes()) + " · out: "
                  + info.decisions().stream().map(DmnModelInfo::describeOutputs).collect(Collectors.joining(", "))));
        }
      }
    } catch (RuntimeException ex) {
      // the editor must open, even if the files of the project cannot be listed
    }
    items.add(SelectItem.of(Conf.CREATE_NEW, NEW_DMN, "✨",
        "Generates a .dmn file from the group New DMN when you open this mask again or run the process. "
            + "You only fill in the rules."));
    return items;
  }

  private static String location(Project project, ProjectDmnFiles.DmnFile file) {
    try {
      if (project != null && file.path().startsWith(project.directory())) {
        return file.relativePath(project.directory());
      }
    } catch (RuntimeException ex) {
      // fall through
    }
    return file.project() + "/" + file.path().getFileName();
  }

  private static Project currentProject() {
    try {
      return Project.current();
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
