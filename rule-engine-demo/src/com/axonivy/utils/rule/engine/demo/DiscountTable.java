package com.axonivy.utils.rule.engine.demo;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.axonivy.utils.rule.engine.RuleEngine;
import com.axonivy.utils.rule.engine.RuleEngineConfig;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * The decision table <code>discount</code> of the model invoice-discount, as the page of the demo shows it: every
 * cell as its FEEL text, read from the version that decided.
 */
public final class DiscountTable {

  public static final String MODEL = "invoice-discount";
  public static final String DECISION = "discount";

  /**
   * @param inputs the input columns, e.g. customerTier
   * @param outputs the output columns, e.g. discountPercent
   */
  public record Table(String hitPolicy, List<String> inputs, List<String> outputs, List<RuleRow> rules) {

    /**
     * The runtime answers the result, not the rule. With the hit policy FIRST the rule that decided is the first one
     * whose outputs are the result.
     *
     * @return the number of that rule, null if no rule has these outputs
     */
    public Integer matching(Invoice invoice) {
      var result = new HashMap<String, Object>();
      result.put("discountPercent", invoice.getDiscountPercent());
      result.put("discountReason", invoice.getDiscountReason());
      for (RuleRow rule : rules) {
        if (gives(rule, result)) {
          return rule.getNumber();
        }
      }
      return null;
    }

    private boolean gives(RuleRow rule, Map<String, Object> result) {
      for (int i = 0; i < outputs.size() && i < rule.getOutputs().size(); i++) {
        Object value = result.get(outputs.get(i));
        if (value != null && !same(rule.getOutputs().get(i), value)) {
          return false;
        }
      }
      return true;
    }

    private static boolean same(String feel, Object value) {
      String text = feel.trim();
      if (value instanceof BigDecimal number) {
        try {
          return new BigDecimal(text).compareTo(number) == 0;
        } catch (NumberFormatException notANumber) {
          return false;
        }
      }
      return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
          && text.substring(1, text.length() - 1).equals(value);
    }
  }

  private DiscountTable() {}

  /** @param version a version of the model on the runtime, e.g. the one that decided; null for the project file */
  public static Table of(Integer version) {
    return read(dmn(version));
  }

  /** @return the table discount of a DMN file */
  public static Table read(String dmn) {
    Element table = decisionTable(parse(dmn));
    var inputs = new ArrayList<String>();
    var outputs = new ArrayList<String>();
    var rules = new ArrayList<RuleRow>();
    for (Element child : children(table)) {
      switch (child.getLocalName()) {
        case "input" -> inputs.add(child.getAttribute("label").isEmpty() ? text(child) : child.getAttribute("label"));
        case "output" -> outputs.add(child.getAttribute("name").isEmpty() ? child.getAttribute("label") : child.getAttribute("name"));
        case "rule" -> rules.add(rule(child, rules.size() + 1));
        default -> { /* annotations */ }
      }
    }
    String hitPolicy = table.getAttribute("hitPolicy").isEmpty() ? "UNIQUE" : table.getAttribute("hitPolicy");
    return new Table(hitPolicy, inputs, outputs, rules);
  }

  private static RuleRow rule(Element rule, int number) {
    var inputs = new ArrayList<String>();
    var outputs = new ArrayList<String>();
    for (Element entry : children(rule)) {
      if ("inputEntry".equals(entry.getLocalName())) {
        inputs.add(text(entry));
      } else if ("outputEntry".equals(entry.getLocalName())) {
        outputs.add(text(entry));
      }
    }
    var row = new RuleRow();
    row.setNumber(number);
    row.setInputs(ch.ivyteam.ivy.scripting.objects.List.create(String.class, inputs));
    row.setOutputs(ch.ivyteam.ivy.scripting.objects.List.create(String.class, outputs));
    row.setMatched(false);
    return row;
  }

  private static Element decisionTable(Document document) {
    var decisions = document.getElementsByTagNameNS("*", "decision");
    for (int i = 0; i < decisions.getLength(); i++) {
      var decision = (Element) decisions.item(i);
      if (DECISION.equals(decision.getAttribute("name"))) {
        var tables = decision.getElementsByTagNameNS("*", "decisionTable");
        if (tables.getLength() > 0) {
          return (Element) tables.item(0);
        }
      }
    }
    throw new IllegalStateException("The model has no decision table '" + DECISION + "'.");
  }

  /** @return the content of the first text element inside, e.g. of an inputEntry */
  private static String text(Element element) {
    var texts = element.getElementsByTagNameNS("*", "text");
    return texts.getLength() == 0 ? "" : texts.item(0).getTextContent().trim();
  }

  private static List<Element> children(Element parent) {
    var children = new ArrayList<Element>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element) {
        children.add(element);
      }
    }
    return children;
  }

  /** @return the content of a version on the runtime, or of the project file if there is no version to read */
  private static String dmn(Integer version) {
    if (version != null) {
      try {
        var config = RuleEngineConfig.fromVariables();
        return RuleEngine.client(config).content(config.runtimeModel(MODEL), version);
      } catch (RuleEngineException | IllegalStateException ex) {
        Ivy.log().info("Rule engine demo: version " + version + " of " + MODEL + " cannot be read, showing the project file. "
            + ex.getMessage());
      }
    }
    return ProjectDmnFiles.find(Project.current(), MODEL)
        .map(ProjectDmnFiles::load)
        .map(ProjectDmnFiles.LoadedDmn::content)
        .orElseThrow(() -> new IllegalStateException("The project has no dmn/" + MODEL + ".dmn"));
  }

  private static Document parse(String dmn) {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(new InputSource(new StringReader(dmn)));
    } catch (ParserConfigurationException | SAXException | IOException ex) {
      throw new IllegalStateException("The model is not a readable DMN file: " + ex.getMessage(), ex);
    }
  }
}
