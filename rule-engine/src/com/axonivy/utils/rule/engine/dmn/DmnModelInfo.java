package com.axonivy.utils.rule.engine.dmn;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * What a .dmn file offers: its decisions, the inputs each decision needs and the results it gives.
 * Read with the XML parser of the JDK, without a DMN engine.
 */
public record DmnModelInfo(String name, String namespace, List<Input> inputs, List<Decision> decisions) {

  /**
   * @param typeRef the type as the file declares it, e.g. <code>tSupplierRating</code>
   * @param type the type for a reader: an item definition resolved to what it is, e.g. <code>string</code>,
   *          <code>list of number</code> or <code>structure</code>; null if the file declares none
   */
  public record Input(String id, String name, String typeRef, String type) {}

  /** A result of a decision, with its type for a reader. */
  public record Output(String name, String type) {}

  /**
   * @param requiredInputs names of all inputs the decision depends on, directly or through other decisions
   * @param outputs what the decision gives: one result named like the decision, or the entries of a structure when
   *          its decision table has several output columns
   */
  public record Decision(String id, String name, String typeRef, List<String> requiredInputs, List<Output> outputs) {}

  public Optional<Decision> decision(String decisionName) {
    return decisions().stream().filter(decision -> decision.name().equals(decisionName)).findFirst();
  }

  public List<String> inputNames() {
    return inputs().stream().map(Input::name).toList();
  }

  /** @return the type of each input for a reader, by its name; inputs without a declared type are left out */
  public Map<String, String> inputTypes() {
    var types = new LinkedHashMap<String, String>();
    inputs().stream().filter(input -> input.type() != null).forEach(input -> types.put(input.name(), input.type()));
    return types;
  }

  /** @return e.g. <code>purchaseAmount (number), type (string)</code> */
  public static String describe(Collection<String> names, Map<String, String> types) {
    return names.stream()
        .map(name -> types.get(name) == null ? name : name + " (" + types.get(name) + ")")
        .collect(Collectors.joining(", "));
  }

  /** @return the inputs a decision needs with their types, e.g. <code>purchaseAmount (number), type (string)</code> */
  public String describeInputs(Decision decision) {
    return describe(decision.requiredInputs(), inputTypes());
  }

  /** @return the results of a decision with their types, e.g. <code>discount (number)</code> */
  public static String describeOutputs(Decision decision) {
    return describe(decision.outputs().stream().map(Output::name).toList(), outputTypes(decision));
  }

  /** @return the type of each result of a decision for a reader, by its name */
  public static Map<String, String> outputTypes(Decision decision) {
    var types = new LinkedHashMap<String, String>();
    decision.outputs().stream().filter(output -> output.type() != null).forEach(output -> types.put(output.name(), output.type()));
    return types;
  }

  /**
   * @param xml content of a .dmn file
   * @throws IllegalArgumentException if the content is not a DMN file
   */
  public static DmnModelInfo parse(String xml) {
    Element definitions = root(xml);
    var items = new HashMap<String, Element>();
    for (Element child : children(definitions)) {
      if ("itemDefinition".equals(child.getLocalName())) {
        items.put(child.getAttribute("name"), child);
      }
    }
    var inputsById = new HashMap<String, Input>();
    var inputs = new ArrayList<Input>();
    var decisionElements = new ArrayList<Element>();
    for (Element child : children(definitions)) {
      switch (child.getLocalName()) {
        case "inputData" -> {
          String typeRef = variableType(child);
          var input = new Input(child.getAttribute("id"), child.getAttribute("name"), typeRef, typeLabel(typeRef, items, 0));
          inputs.add(input);
          inputsById.put(input.id(), input);
        }
        case "decision" -> decisionElements.add(child);
        default -> {
          // item definitions (read above), knowledge models, diagram: not needed to call a decision
        }
      }
    }
    var requirements = new HashMap<String, Requirements>();
    for (Element decision : decisionElements) {
      requirements.put(decision.getAttribute("id"), requirements(decision));
    }
    var decisions = new ArrayList<Decision>();
    for (Element decision : decisionElements) {
      var inputIds = new LinkedHashSet<String>();
      collectInputs(decision.getAttribute("id"), requirements, inputIds, new LinkedHashSet<>());
      var names = inputIds.stream()
          .map(inputsById::get)
          .filter(java.util.Objects::nonNull)
          .map(Input::name)
          .toList();
      decisions.add(new Decision(decision.getAttribute("id"), decision.getAttribute("name"),
          variableType(decision), names, outputs(decision, items)));
    }
    return new DmnModelInfo(definitions.getAttribute("name"), definitions.getAttribute("namespace"),
        List.copyOf(inputs), List.copyOf(decisions));
  }

  /**
   * One result named like the decision; or, when its decision table has several output columns, the entries of the
   * structure it gives, named like the columns.
   */
  private static List<Output> outputs(Element decision, Map<String, Element> items) {
    String name = decision.getAttribute("name");
    String typeRef = variableType(decision);
    for (Element child : children(decision)) {
      if (!"decisionTable".equals(child.getLocalName())) {
        continue;
      }
      var columns = children(child).stream().filter(column -> "output".equals(column.getLocalName())).toList();
      if (columns.size() > 1) {
        return columns.stream()
            .map(column -> new Output(column.hasAttribute("name") ? column.getAttribute("name") : column.getAttribute("label"),
                typeLabel(column.getAttribute("typeRef"), items, 0)))
            .toList();
      }
      if (typeRef == null && columns.size() == 1) {
        typeRef = columns.get(0).getAttribute("typeRef");
      }
    }
    return List.of(new Output(name, typeLabel(typeRef, items, 0)));
  }

  /**
   * A type for a reader: a FEEL type as it is (<code>number</code>, <code>date and time</code>), an item definition
   * resolved to what it stands for (<code>tSupplierRating</code> → <code>string</code>), a structure as
   * <code>structure</code>, a collection as <code>list of …</code>.
   */
  private static String typeLabel(String typeRef, Map<String, Element> items, int depth) {
    if (typeRef == null || typeRef.isBlank()) {
      return null;
    }
    String name = typeRef.strip();
    int prefix = name.indexOf(':');
    if (prefix > 0 && !items.containsKey(name)) {
      name = name.substring(prefix + 1); // feel:string, or a type of a namespace of the file
    }
    Element item = items.get(name);
    if (item == null || depth > 8) {
      return name;
    }
    String base = name;
    for (Element child : children(item)) {
      if ("typeRef".equals(child.getLocalName())) {
        base = typeLabel(child.getTextContent(), items, depth + 1);
        break;
      }
      if ("itemComponent".equals(child.getLocalName())) {
        base = "structure";
        break;
      }
    }
    return "true".equals(item.getAttribute("isCollection")) ? "list of " + base : base;
  }

  private record Requirements(List<String> inputs, List<String> decisions) {}

  private static Requirements requirements(Element decision) {
    var inputs = new ArrayList<String>();
    var decisions = new ArrayList<String>();
    for (Element requirement : children(decision)) {
      if (!"informationRequirement".equals(requirement.getLocalName())) {
        continue;
      }
      for (Element required : children(requirement)) {
        String id = id(required.getAttribute("href"));
        if ("requiredInput".equals(required.getLocalName())) {
          inputs.add(id);
        } else if ("requiredDecision".equals(required.getLocalName())) {
          decisions.add(id);
        }
      }
    }
    return new Requirements(inputs, decisions);
  }

  private static void collectInputs(String decisionId, Map<String, Requirements> requirements, Set<String> inputIds,
      Set<String> visited) {
    if (!visited.add(decisionId)) {
      return;
    }
    var required = requirements.get(decisionId);
    if (required == null) {
      return;
    }
    inputIds.addAll(required.inputs());
    required.decisions().forEach(id -> collectInputs(id, requirements, inputIds, visited));
  }

  private static String id(String href) {
    int hash = href.lastIndexOf('#');
    return hash < 0 ? href : href.substring(hash + 1);
  }

  private static String variableType(Element element) {
    for (Element child : children(element)) {
      if ("variable".equals(child.getLocalName())) {
        return child.hasAttribute("typeRef") ? child.getAttribute("typeRef") : null;
      }
    }
    return null;
  }

  private static List<Element> children(Element parent) {
    var children = new ArrayList<Element>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && element.getLocalName() != null) {
        children.add(element);
      }
    }
    return children;
  }

  private static Element root(String xml) {
    Element root = parseXml(xml);
    if (!"definitions".equals(root.getLocalName())) {
      throw new IllegalArgumentException("Not a DMN file: the root element is <" + root.getNodeName() + ">");
    }
    return root;
  }

  private static Element parseXml(String xml) {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(null);
      return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    } catch (ParserConfigurationException | SAXException | IOException | RuntimeException ex) {
      throw new IllegalArgumentException("Not a readable DMN file: " + ex.getMessage(), ex);
    }
  }
}
