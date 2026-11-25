package com.axonivy.utils.rule.engine.runtime.dmn;

import static org.kie.dmn.validation.DMNValidator.Validation.VALIDATE_COMPILATION;
import static org.kie.dmn.validation.DMNValidator.Validation.VALIDATE_MODEL;
import static org.kie.dmn.validation.DMNValidator.Validation.VALIDATE_SCHEMA;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.kie.api.io.Resource;
import org.kie.dmn.core.compiler.RuntimeTypeCheckOption;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.api.core.DMNType;
import org.kie.dmn.api.core.ast.DecisionNode;
import org.kie.dmn.api.core.ast.InputDataNode;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.dmn.validation.DMNValidator;
import org.kie.dmn.validation.DMNValidatorFactory;
import org.kie.internal.io.ResourceFactory;

import com.axonivy.utils.rule.engine.runtime.api.model.Message;
import com.axonivy.utils.rule.engine.runtime.api.model.ModelInfo;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Turns DMN XML into an executable model at runtime. No code generation, no build, no restart.
 */
@ApplicationScoped
public class DmnCompiler {

  private DMNValidator validator;

  /**
   * Strict mode: an input that does not fit the declared type, e.g. a value outside of the allowed values
   * of an enum, fails the evaluation instead of silently matching no rule.
   */
  @ConfigProperty(name = "rule.engine.type-check", defaultValue = "true")
  boolean typeCheck = true;

  /**
   * @return the executable model
   * @throws InvalidModelException with readable messages if the content cannot be loaded
   */
  public CompiledModel compile(String model, int version, String xml) {
    DMNRuntime runtime = buildRuntime(xml);
    if (runtime.getModels().isEmpty()) {
      throw new InvalidModelException(List.of(Message.error("The file contains no DMN model.")));
    }
    DMNModel dmnModel = runtime.getModels().get(0);
    if (dmnModel.hasErrors()) {
      throw new InvalidModelException(toMessages(dmnModel.getMessages()));
    }
    return new CompiledModel(model, version, dmnModel.getName(), dmnModel.getNamespace(),
        decisions(dmnModel), inputs(dmnModel), runtime, dmnModel);
  }

  /**
   * Checks the content against the DMN schema, the DMN model rules and the compiler.
   * @return all findings, errors and warnings
   */
  public List<Message> validate(String xml) {
    if (xml == null || xml.isBlank()) {
      return List.of(Message.error("The DMN content is empty."));
    }
    var notDmn = checkIsDmnDocument(xml);
    if (notDmn != null) {
      return List.of(Message.error(notDmn));
    }
    try {
      List<DMNMessage> messages;
      synchronized (this) {
        messages = validator().validate(new StringReader(xml), VALIDATE_SCHEMA, VALIDATE_MODEL, VALIDATE_COMPILATION);
      }
      var findings = toMessages(messages);
      boolean schemaFailed = findings.stream().anyMatch(message -> "FAILED_XML_VALIDATION".equals(message.type()));
      if (schemaFailed) {
        // the validator stops after a schema error and reports that as a second, technical finding
        findings.removeIf(message -> "FAILED_VALIDATION".equals(message.type()));
      }
      return findings;
    } catch (Exception ex) {
      return List.of(Message.error("The content is not a readable DMN file: " + rootMessage(ex)));
    }
  }

  /**
   * Validates and compiles in one go.
   * @throws InvalidModelException if the validator or the compiler reports an error
   */
  public CompiledModel validateAndCompile(String model, int version, String xml) {
    var findings = validate(xml);
    if (findings.stream().anyMatch(Message::isError)) {
      throw new InvalidModelException(findings);
    }
    return compile(model, version, xml);
  }

  /**
   * @return why the content is not a DMN document, or null if it is one
   */
  private static String checkIsDmnDocument(String xml) {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(null);
      var root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
      String namespace = root.getNamespaceURI();
      boolean dmn = "definitions".equals(root.getLocalName())
          && namespace != null && namespace.contains("omg.org/spec/DMN/");
      if (!dmn) {
        return "The file is not a DMN model: expected the root element <definitions> in a DMN namespace, but found <"
            + root.getNodeName() + ">" + (namespace == null ? " without namespace" : " in namespace " + namespace)
            + ".";
      }
      return null;
    } catch (SAXParseException ex) {
      return "The file is not well-formed XML (line " + ex.getLineNumber() + ", column " + ex.getColumnNumber()
          + "): " + ex.getMessage();
    } catch (Exception ex) {
      return "The file is not readable XML: " + rootMessage(ex);
    }
  }

  private DMNRuntime buildRuntime(String xml) {
    if (xml == null || xml.isBlank()) {
      throw new InvalidModelException(List.of(Message.error("The DMN content is empty.")));
    }
    try {
      Resource resource = ResourceFactory.newReaderResource(new StringReader(xml), "UTF-8");
      return DMNRuntimeBuilder.fromDefaults()
          .setOption(new RuntimeTypeCheckOption(typeCheck))
          .buildConfiguration()
          .fromResources(List.of(resource))
          .getOrElseThrow(ex -> new InvalidModelException(
              List.of(Message.error("The content is not a readable DMN file: " + rootMessage(ex)))));
    } catch (InvalidModelException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new InvalidModelException(
          List.of(Message.error("The content is not a readable DMN file: " + rootMessage(ex))));
    }
  }

  private static List<ModelInfo.Decision> decisions(DMNModel dmnModel) {
    return dmnModel.getDecisions().stream()
        .sorted(Comparator.comparing(DecisionNode::getName))
        .map(decision -> new ModelInfo.Decision(
            decision.getName(),
            typeName(decision.getResultType()),
            dmnModel.getRequiredInputsForDecisionName(decision.getName()).stream()
                .map(InputDataNode::getName)
                .sorted()
                .toList()))
        .toList();
  }

  private static List<ModelInfo.Input> inputs(DMNModel dmnModel) {
    return dmnModel.getInputs().stream()
        .sorted(Comparator.comparing(InputDataNode::getName))
        .map(input -> new ModelInfo.Input(
            input.getName(),
            typeName(input.getType()),
            input.getType() != null && input.getType().isCollection()))
        .toList();
  }

  private static String typeName(DMNType type) {
    return type == null ? null : type.getName();
  }

  private static List<Message> toMessages(List<DMNMessage> messages) {
    var result = new ArrayList<Message>();
    for (DMNMessage message : messages) {
      var converted = Messages.of(message);
      if (!result.contains(converted)) {
        result.add(converted);
      }
    }
    return result;
  }

  private static String rootMessage(Throwable ex) {
    Throwable root = ex;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    var message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    // XML parser messages can span many lines, the first one names the problem
    return message.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse(message);
  }

  private DMNValidator validator() {
    if (validator == null) {
      validator = DMNValidatorFactory.newValidator();
    }
    return validator;
  }

  @PreDestroy
  void dispose() {
    if (validator != null) {
      validator.dispose();
    }
  }
}
