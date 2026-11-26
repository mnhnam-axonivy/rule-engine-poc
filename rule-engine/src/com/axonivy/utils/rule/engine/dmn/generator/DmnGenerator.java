package com.axonivy.utils.rule.engine.dmn.generator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class DmnGenerator {

  public static final String DMN_NAMESPACE = "https://www.omg.org/spec/DMN/20230324/MODEL/";
  public static final String FEEL_NAMESPACE = "https://www.omg.org/spec/DMN/20230324/FEEL/";
  private static final String DMNDI_NAMESPACE = "https://www.omg.org/spec/DMN/20230324/DMNDI/";
  private static final String DC_NAMESPACE = "http://www.omg.org/spec/DMN/20180521/DC/";
  private static final String DI_NAMESPACE = "http://www.omg.org/spec/DMN/20180521/DI/";
  private static final String MODEL_NAMESPACE_PREFIX = "https://axonivy.com/dmn/";

  private static final int MAX_COLUMN_DEPTH = 3;

  private static final int NODE_WIDTH = 160;
  private static final int NODE_HEIGHT = 80;
  private static final int NODE_GAP = 40;
  private static final int MARGIN = 60;
  private static final int DECISION_Y = 80;
  private static final int INPUT_Y = 300;

  private record Column(String expression, String typeRef) {}

  public String generate(DmnSpec spec) {
    var types = new TypeDefinitions();
    spec.inputs().forEach(input -> types.register(input.type()));
    spec.outputs().forEach(output -> types.register(output.type()));

    String decision = spec.decisionName();
    String decisionType = decisionType(spec, decision, types);

    var xml = new Xml();
    xml.line("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    xml.open("definitions",
        "xmlns", DMN_NAMESPACE,
        "xmlns:dmndi", DMNDI_NAMESPACE,
        "xmlns:dc", DC_NAMESPACE,
        "xmlns:di", DI_NAMESPACE,
        "id", id("definitions", spec.modelName()),
        "name", spec.modelName(),
        "namespace", MODEL_NAMESPACE_PREFIX + id("", spec.modelName()).substring(1),
        "expressionLanguage", FEEL_NAMESPACE,
        "typeLanguage", FEEL_NAMESPACE,
        "exporter", "Axon Ivy Rules Engine");
    if (spec.description() != null) {
      xml.text("description", spec.description());
    }
    types.write(xml);
    for (DmnField input : spec.inputs()) {
      xml.open("inputData", "id", id("input", input.name()), "name", input.name());
      xml.empty("variable", "id", id("input_var", input.name()), "name", input.name(),
          "typeRef", types.ref(input.type()));
      xml.close("inputData");
    }
    writeDecision(xml, spec, decision, decisionType, types);
    writeDiagram(xml, spec, decision);
    xml.close("definitions");
    return xml.toString();
  }

  private static String decisionType(DmnSpec spec, String decision, TypeDefinitions types) {
    if (spec.outputs().size() == 1) {
      return types.ref(spec.outputs().get(0).type());
    }
    var result = new DmnType.Structure(types.freeName("t" + capitalize(decision)), spec.outputs());
    types.register(result);
    return result.name();
  }

  private static void writeDecision(Xml xml, DmnSpec spec, String decision, String decisionType,
      TypeDefinitions types) {
    xml.open("decision", "id", id("decision", decision), "name", decision);
    xml.empty("variable", "id", id("decision_var", decision), "name", decision, "typeRef", decisionType);
    for (DmnField input : spec.inputs()) {
      xml.open("informationRequirement", "id", id("requirement", input.name()));
      xml.empty("requiredInput", "href", "#" + id("input", input.name()));
      xml.close("informationRequirement");
    }
    xml.open("decisionTable", "id", id("table", decision), "hitPolicy", "FIRST");
    var columns = new ArrayList<Column>();
    spec.inputs().forEach(input -> columns.addAll(columns(input, types)));
    int index = 0;
    for (Column column : columns) {
      index++;
      xml.open("input", "id", id("table_input", String.valueOf(index)), "label", column.expression());
      if (column.typeRef() == null) {
        xml.open("inputExpression", "id", id("table_input_expression", String.valueOf(index)));
      } else {
        xml.open("inputExpression", "id", id("table_input_expression", String.valueOf(index)),
            "typeRef", column.typeRef());
      }
      xml.text("text", column.expression());
      xml.close("inputExpression");
      xml.close("input");
    }
    boolean named = spec.outputs().size() > 1;
    for (DmnField output : spec.outputs()) {
      if (named) {
        xml.empty("output", "id", id("table_output", output.name()), "name", output.name(),
            "typeRef", types.ref(output.type()));
      } else {
        // a single output takes its type from the decision, a type reference here is flagged by the validator
        xml.empty("output", "id", id("table_output", output.name()), "label", output.name());
      }
    }
    xml.empty("annotation", "name", "Description");
    xml.open("rule", "id", id("rule", "1"));
    for (int column = 1; column <= columns.size(); column++) {
      xml.open("inputEntry", "id", id("rule_1_input", String.valueOf(column)));
      xml.text("text", "-");
      xml.close("inputEntry");
    }
    for (DmnField output : spec.outputs()) {
      xml.open("outputEntry", "id", id("rule_1_output", output.name()));
      xml.text("text", placeholder(output.type()));
      xml.close("outputEntry");
    }
    xml.open("annotationEntry");
    xml.text("text", "Placeholder: matches everything. Add your rules above this one.");
    xml.close("annotationEntry");
    xml.close("rule");
    xml.close("decisionTable");
    xml.close("decision");
  }

  private static List<Column> columns(DmnField input, TypeDefinitions types) {
    var columns = new ArrayList<Column>();
    if (input.type() instanceof DmnType.Structure structure) {
      unfold(input.name(), structure, 1, types, columns);
      if (columns.isEmpty()) {
        columns.add(new Column(input.name(), null));
      }
    } else {
      columns.add(new Column(input.name(), types.ref(input.type())));
    }
    return columns;
  }

  private static void unfold(String path, DmnType.Structure structure, int depth, TypeDefinitions types,
      List<Column> columns) {
    for (DmnField field : structure.fields()) {
      String fieldPath = path + "." + FeelNames.toFeelName(field.name());
      if (field.type() instanceof DmnType.Structure nested) {
        if (depth < MAX_COLUMN_DEPTH) {
          unfold(fieldPath, nested, depth + 1, types, columns);
        }
      } else if (!(field.type() instanceof DmnType.Collection)) {
        columns.add(new Column(fieldPath, types.ref(field.type())));
      }
    }
  }

  private static String placeholder(DmnType type) {
    if (type instanceof DmnType.Enumeration enumeration && !enumeration.values().isEmpty()) {
      return quote(enumeration.values().get(0));
    }
    if (type == DmnType.NUMBER) {
      return "0";
    }
    if (type == DmnType.STRING) {
      return "\"\"";
    }
    if (type == DmnType.BOOLEAN) {
      return "false";
    }
    return "null";
  }

  private static void writeDiagram(Xml xml, DmnSpec spec, String decision) {
    int inputs = spec.inputs().size();
    int totalWidth = inputs * NODE_WIDTH + (inputs - 1) * NODE_GAP;
    int decisionX = MARGIN + (totalWidth - NODE_WIDTH) / 2;

    xml.open("dmndi:DMNDI");
    xml.open("dmndi:DMNDiagram", "id", id("diagram", spec.modelName()), "name", "Default DRD",
        "useAlternativeInputDataShape", "false");
    shape(xml, id("decision", decision), decisionX, DECISION_Y);
    int index = 0;
    for (DmnField input : spec.inputs()) {
      int x = MARGIN + index * (NODE_WIDTH + NODE_GAP);
      shape(xml, id("input", input.name()), x, INPUT_Y);
      xml.open("dmndi:DMNEdge", "id", id("edge", input.name()),
          "dmnElementRef", id("requirement", input.name()),
          "sourceElement", id("shape", id("input", input.name())),
          "targetElement", id("shape", id("decision", decision)));
      xml.empty("di:waypoint", "x", String.valueOf(x + NODE_WIDTH / 2), "y", String.valueOf(INPUT_Y));
      xml.empty("di:waypoint", "x", String.valueOf(decisionX + NODE_WIDTH / 2),
          "y", String.valueOf(DECISION_Y + NODE_HEIGHT));
      xml.close("dmndi:DMNEdge");
      index++;
    }
    xml.close("dmndi:DMNDiagram");
    xml.close("dmndi:DMNDI");
  }

  private static void shape(Xml xml, String elementId, int x, int y) {
    xml.open("dmndi:DMNShape", "id", id("shape", elementId), "dmnElementRef", elementId,
        "isCollapsed", "false", "isListedInputData", "false");
    xml.empty("dc:Bounds", "x", String.valueOf(x), "y", String.valueOf(y),
        "width", String.valueOf(NODE_WIDTH), "height", String.valueOf(NODE_HEIGHT));
    xml.close("dmndi:DMNShape");
  }

  /** A stable XML id, so generating the same model twice gives the same file. */
  private static String id(String kind, String name) {
    var id = new StringBuilder("_");
    if (!kind.isEmpty()) {
      id.append(kind).append('_');
    }
    for (char c : name.toCharArray()) {
      id.append(Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-' ? c : '_');
    }
    return id.toString();
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String capitalize(String name) {
    return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
  }

  private static final class TypeDefinitions {

    private final Map<String, DmnType> definitions = new LinkedHashMap<>();

    String ref(DmnType type) {
      if (type instanceof DmnType.Simple simple) {
        return simple.feelName();
      }
      if (type instanceof DmnType.Enumeration enumeration) {
        return enumeration.name();
      }
      if (type instanceof DmnType.Structure structure) {
        return structure.name();
      }
      if (type instanceof DmnType.Collection collection) {
        return listName(collection);
      }
      return "Any";
    }

    private String listName(DmnType.Collection collection) {
      String element = ref(collection.element());
      if (collection.element() instanceof DmnType.Simple) {
        element = "t" + java.util.Arrays.stream(element.split(" "))
            .map(DmnGenerator::capitalize)
            .collect(Collectors.joining());
      }
      return element + "List";
    }

    /** Registers the type and everything it is built from. Parts first, so the file reads top-down. */
    void register(DmnType type) {
      if (type instanceof DmnType.Structure structure) {
        structure.fields().forEach(field -> register(field.type()));
        definitions.putIfAbsent(structure.name(), structure);
      } else if (type instanceof DmnType.Enumeration enumeration) {
        definitions.putIfAbsent(enumeration.name(), enumeration);
      } else if (type instanceof DmnType.Collection collection) {
        register(collection.element());
        definitions.putIfAbsent(listName(collection), collection);
      }
    }

    String freeName(String wanted) {
      String name = wanted;
      for (int index = 2; definitions.containsKey(name); index++) {
        name = wanted + index;
      }
      return name;
    }

    void write(Xml xml) {
      definitions.forEach((name, type) -> {
        if (type instanceof DmnType.Enumeration enumeration) {
          xml.open("itemDefinition", "id", id("type", name), "name", name, "isCollection", "false");
          xml.text("typeRef", "string");
          xml.open("allowedValues", "id", id("type_values", name));
          // null stays allowed: a field that is not set must not fail the decision
          xml.text("text", enumeration.values().stream().map(DmnGenerator::quote).collect(Collectors.joining(", "))
              + ", null");
          xml.close("allowedValues");
          xml.close("itemDefinition");
        } else if (type instanceof DmnType.Collection collection) {
          xml.open("itemDefinition", "id", id("type", name), "name", name, "isCollection", "true");
          xml.text("typeRef", ref(collection.element()));
          xml.close("itemDefinition");
        } else if (type instanceof DmnType.Structure structure) {
          xml.open("itemDefinition", "id", id("type", name), "name", name, "isCollection", "false");
          for (DmnField field : structure.fields()) {
            String component = FeelNames.toFeelName(field.name());
            boolean list = field.type() instanceof DmnType.Collection;
            DmnType componentType = list ? ((DmnType.Collection) field.type()).element() : field.type();
            xml.open("itemComponent", "id", id("type", name + "_" + component), "name", component,
                "isCollection", String.valueOf(list));
            xml.text("typeRef", ref(componentType));
            xml.close("itemComponent");
          }
          xml.close("itemDefinition");
        }
      });
    }
  }

  private static final class Xml {

    private final StringBuilder out = new StringBuilder();
    private int level;

    void line(String line) {
      out.append(line).append('\n');
    }

    void open(String element, String... attributes) {
      indent();
      out.append('<').append(element);
      attributes(attributes);
      out.append(">\n");
      level++;
    }

    void empty(String element, String... attributes) {
      indent();
      out.append('<').append(element);
      attributes(attributes);
      out.append("/>\n");
    }

    void text(String element, String text) {
      indent();
      out.append('<').append(element).append('>').append(escape(text)).append("</").append(element).append(">\n");
    }

    void close(String element) {
      level--;
      indent();
      out.append("</").append(element).append(">\n");
    }

    private void attributes(String... attributes) {
      for (int i = 0; i + 1 < attributes.length; i += 2) {
        out.append(' ').append(attributes[i]).append("=\"").append(escape(attributes[i + 1])).append('"');
      }
    }

    private void indent() {
      out.append("  ".repeat(level));
    }

    private static String escape(String value) {
      return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public String toString() {
      return out.toString();
    }
  }
}
