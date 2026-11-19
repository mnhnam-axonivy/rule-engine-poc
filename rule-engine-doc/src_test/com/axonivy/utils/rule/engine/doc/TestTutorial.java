package com.axonivy.utils.rule.engine.doc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.environment.IvyTest;
import ch.ivyteam.ivy.workflow.IProcessStart;
import ch.ivyteam.ivy.workflow.IWorkflowProcessModelVersion;

/**
 * The tutorial: its process start, its pages in the CMS and the files of the page.
 * How the page looks on an engine is tested by {@link TutorialWebIT}.
 */
@IvyTest
class TestTutorial {

  private static final String CMS = "/Dialogs/com/axonivy/utils/rule/engine/doc/Tutorial/";

  @Test
  void processStart() {
    IProcessStart start = IWorkflowProcessModelVersion.of(Project.current()).getProcessStarts().stream()
        .filter(candidate -> "RuleEngineTutorial/tutorial.ivp".equals(candidate.getUserFriendlyRequestPath()))
        .findFirst().orElse(null);

    assertThat(start).as("process start of the tutorial").isNotNull();
    assertThat(start.getName()).isEqualTo("Rule Engine Tutorial");
    assertThat(start.getLink().getRelative()).endsWith("/pro/rule-engine-doc/19A8D1C0FFEE0003/tutorial.ivp");
  }

  @Test
  void pagesAreInTheCms() {
    var pages = TutorialPages.load();

    assertThat(pages.keySet()).as("from easy to hard")
        .containsExactly("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11");
    for (String id : TutorialPages.PAGE_IDS) {
      assertThat(pages.get(id)).as("Markdown of page " + id).startsWith("# ").contains("\n## ");
    }
    assertThat(pages.get("01")).startsWith("# What is DMN?").contains("## Read a decision table", "## First match wins");
    assertThat(pages.get("02")).startsWith("# How the rule engine works").contains("## The decision runtime container");
    assertThat(pages.get("03")).contains("## Prerequisites").contains("docker compose up -d");
    assertThat(pages.get("04")).startsWith("# Use an existing DMN decision")
        .contains("## Which fields do I have to fill in?", "## How does the element find a field by name?")
        .contains("## How do I prepare a value for the decision?");
    assertThat(pages.get("05")).startsWith("# Create a new DMN from scratch").contains("## Name the new DMN");
    assertThat(pages.get("06")).startsWith("# Change rules without deployment").contains("## Which version decided?");
    assertThat(pages.get("07")).startsWith("# Edit rules in the Portal").contains("## Who has the last word");
    assertThat(pages.get("09")).startsWith("# Run it in production").contains("## Checklist");
    assertThat(pages.get("10")).startsWith("# Several decisions and the Java API").contains("## The Java API");
    assertThat(pages.get("11")).as("the questions, in groups")
        .startsWith("# Frequently asked questions")
        .contains("## Setup", "## Mapping and fields", "## When rules change", "## Errors")
        .contains("### How do I go back to the version before?");
  }

  @Test
  void everyPageHasItsStep() {
    assertThat(Ivy.cms().co(CMS + "HeroTitle")).isEqualTo("Axon Ivy Rules Engine Tutorial");
    assertThat(Ivy.cms().co(CMS + "LevelBasics")).as("the first level of the path").isEqualTo("Basics");
    for (String id : TutorialPages.PAGE_IDS) {
      assertThat(Ivy.cms().co(CMS + "Page" + id + "Title")).as("title of page " + id).isNotBlank();
      assertThat(Ivy.cms().co(CMS + "Page" + id + "Desc")).as("description of page " + id).isNotBlank();
    }
  }

  @Test
  void pageHasItsLayoutAndScripts() {
    Path web = Project.current().directory().resolve("webContent");

    assertThat(web.resolve("layouts/frame-10-full-width.xhtml")).as("layout of the dialog").exists();
    for (String script : List.of("tutorial.js", "marked.min.js", "highlight.min.js", "interactive-runtime.min.js",
        "guide-01.js", "guide-02.js", "guide-03.js", "guide-04.js", "guide-05.js", "guide-06.js", "guide-07.js",
        "guide-08.js", "guide-11-3.js")) {
      assertThat(web.resolve("resources/js").resolve(script)).as(script).exists().isNotEmptyFile();
    }
    assertThat(Files.exists(web.resolve("resources/css/tutorial.css"))).isTrue();
  }

  @Test
  void runsNextToTheDemo() {
    // a project from the local Maven repository carries its version in the name: rule-engine-demo-1401-SNAPSHOT
    List<String> required = Project.current().allRequiredProjects()
        .map(project -> project.name().replaceFirst("-\\d+(-SNAPSHOT)?$", ""))
        .toList();

    assertThat(required).as("the projects the tutorial explains").contains("rule-engine-demo", "rule-engine", "rule-engine-core");
  }
}
