package com.axonivy.utils.rule.engine.demo;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.axonivy.utils.rule.engine.RuleEngine;
import com.axonivy.utils.rule.engine.RuleEngineConfig;
import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.mapping.ValueConverter;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.scripting.objects.List;

/**
 * The tax formula of the model invoice-pricing: the business knowledge model <code>taxFormula(net, rate)</code>,
 * a FEEL literal expression. The demo shows it, explains it line by line, and lets a developer try another one.
 */
public final class TaxFormula {

  public static final String MODEL = "invoice-pricing";
  /** Name of the business knowledge model that holds the formula. */
  public static final String FUNCTION = "taxFormula";
  public static final String DEFAULT = "round half up(net * rate / 100, 2)";

  private static final int MAX_LENGTH = 500;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  /** A FEEL function definition can call Java (external functions): not for a formula typed into a page. */
  private static final Pattern FUNCTION_DEFINITION = Pattern.compile("\\b(function|external)\\b");
  private static final Pattern DMN_ID = Pattern.compile("\\s*\\(DMN id: [^)]*\\)\\s*$");

  private TaxFormula() {}

  /**
   * @param version a version of the model on the runtime, e.g. the one that decided; null for the .dmn file of the
   *          project
   * @return the FEEL expression of taxFormula in that version, null if it has no such function
   */
  public static String of(Integer version) {
    return formula(dmn(version));
  }

  /** @return how each priced line got its VAT: the formula with the values of the line */
  public static List<TaxStep> steps(PricingResult pricing, String formula) {
    var steps = new ArrayList<TaxStep>();
    if (pricing != null && pricing.getPricedLines() != null) {
      for (PricedLine line : pricing.getPricedLines()) {
        var step = new TaxStep();
        step.setProduct(line.getProduct());
        step.setNet(line.getNet());
        step.setRate(line.getVatRate());
        step.setVat(line.getVat());
        if (line.getNet() != null && line.getVatRate() != null) {
          step.setExact(line.getNet().multiply(line.getVatRate()).divide(HUNDRED));
          step.setFormula(formula == null ? null : withValues(formula, line.getNet(), line.getVatRate()));
        }
        steps.add(step);
      }
    }
    return List.create(TaxStep.class, steps);
  }

  /**
   * Prices the invoice with another tax formula, on a copy of the model that only lives for this call: the copy is
   * loaded on the runtime under a name of its own, evaluated and deleted. The live rules do not change.
   *
   * @param version the version of the model to copy, e.g. the one that decided
   */
  public static TaxTrial tryFormula(SalesInvoice invoice, Integer version, String formula) {
    var trial = new TaxTrial();
    trial.setFormula(formula);
    String refused = refused(formula);
    if (refused != null) {
      trial.setErrorMessage(refused);
      return trial;
    }
    var config = RuleEngineConfig.fromVariables();
    RuleEngineClient client;
    try {
      client = RuleEngine.client(config);
    } catch (IllegalStateException ex) {
      trial.setErrorMessage(ex.getMessage());
      return trial;
    }
    String copy = config.runtimeModel(MODEL) + "-try-" + UUID.randomUUID().toString().substring(0, 8);
    try {
      client.registerVersion(copy, RegisterVersionRequest.builder()
          .content(withFormula(dmn(version), formula.trim()))
          .author(user())
          .description("A tax formula tried in the demo, deleted after the evaluation")
          .source(VersionSource.API)
          .activate(true)
          .build());
      @SuppressWarnings("unchecked")
      var input = (Map<String, Object>) ValueConverter.toJson(invoice);
      var results = client.evaluate(copy, EvaluateRequest.builder().input(input).build()).results();
      trial.setPricedLines(pricedLines(results.get("pricedLines")));
      trial.setTotals((InvoiceTotals) ValueConverter.fromJson(results.get("totals"), InvoiceTotals.class));
    } catch (RuleEngineApiException ex) {
      trial.setErrorMessage(readable(ex));
    } catch (RuleEngineException | IllegalArgumentException | IllegalStateException ex) {
      trial.setErrorMessage(ex.getMessage());
    } finally {
      delete(client, copy);
    }
    return trial;
  }

  /** @return why a formula is not tried, null if it is */
  public static String refused(String formula) {
    if (formula == null || formula.isBlank()) {
      return "Enter a FEEL expression with net and rate, e.g. " + DEFAULT;
    }
    if (formula.length() > MAX_LENGTH) {
      return "The formula is longer than " + MAX_LENGTH + " characters.";
    }
    if (FUNCTION_DEFINITION.matcher(formula).find()) {
      return "Function definitions are not allowed here: the formula runs on the shared decision runtime.";
    }
    return null;
  }

  /** @return the formula with net and rate replaced by values, e.g. round half up(11280.00 * 8.1 / 100, 2) */
  public static String withValues(String formula, BigDecimal net, BigDecimal rate) {
    String text = formula.replaceAll("\\bnet\\b", Matcher.quoteReplacement(net.toPlainString()));
    return text.replaceAll("\\brate\\b", Matcher.quoteReplacement(rate.toPlainString()));
  }

  /** @return the FEEL text of taxFormula in a DMN file, null if the file has no such function */
  public static String formula(String dmn) {
    Element text = formulaText(parse(dmn));
    return text == null ? null : text.getTextContent().trim();
  }

  /** @return the DMN file with another FEEL text for taxFormula */
  public static String withFormula(String dmn, String formula) {
    Document document = parse(dmn);
    Element text = formulaText(document);
    if (text == null) {
      throw new IllegalStateException("The model has no business knowledge model '" + FUNCTION + "' with a literal expression.");
    }
    text.setTextContent(formula);
    try {
      var factory = TransformerFactory.newInstance();
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var writer = new StringWriter();
      factory.newTransformer().transform(new DOMSource(document), new StreamResult(writer));
      return writer.toString();
    } catch (TransformerException ex) {
      throw new IllegalStateException("Cannot write the model with the new formula", ex);
    }
  }

  private static Element formulaText(Document document) {
    NodeList functions = document.getElementsByTagNameNS("*", "businessKnowledgeModel");
    for (int i = 0; i < functions.getLength(); i++) {
      var function = (Element) functions.item(i);
      if (!FUNCTION.equals(function.getAttribute("name"))) {
        continue;
      }
      NodeList expressions = function.getElementsByTagNameNS("*", "literalExpression");
      if (expressions.getLength() > 0) {
        NodeList texts = ((Element) expressions.item(0)).getElementsByTagNameNS("*", "text");
        return texts.getLength() > 0 ? (Element) texts.item(0) : null;
      }
    }
    return null;
  }

  /** @return the content of a version on the runtime, or of the project file if there is no version to read */
  private static String dmn(Integer version) {
    if (version != null) {
      try {
        var config = RuleEngineConfig.fromVariables();
        return RuleEngine.client(config).content(config.runtimeModel(MODEL), version);
      } catch (RuleEngineException | IllegalStateException ex) {
        Ivy.log().info("Rule engine demo: version " + version + " of " + MODEL + " cannot be read, using the project file. "
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

  private static List<PricedLine> pricedLines(Object json) {
    var lines = new ArrayList<PricedLine>();
    if (json instanceof Collection<?> collection) {
      for (Object line : collection) {
        lines.add((PricedLine) ValueConverter.fromJson(line, PricedLine.class));
      }
    }
    return List.create(PricedLine.class, lines);
  }

  private static String readable(RuleEngineApiException ex) {
    String message = ex.details() == null || ex.details().isEmpty() ? ex.getMessage() : ex.details().get(0).message();
    return DMN_ID.matcher(message).replaceFirst("");
  }

  private static String user() {
    try {
      return Ivy.session().getSessionUserName();
    } catch (RuntimeException noSession) {
      return null;
    }
  }

  private static void delete(RuleEngineClient client, String model) {
    try {
      client.deleteModel(model);
    } catch (RuleEngineApiException ex) {
      if (!RuleEngineApiException.MODEL_NOT_FOUND.equals(ex.code())) {
        Ivy.log().warn("Rule engine demo: the copy " + model + " was not deleted: " + ex.getMessage());
      }
    } catch (RuleEngineException ex) {
      Ivy.log().warn("Rule engine demo: the copy " + model + " was not deleted: " + ex.getMessage());
    }
  }
}
