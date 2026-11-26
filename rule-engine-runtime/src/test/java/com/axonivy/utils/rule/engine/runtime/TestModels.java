package com.axonivy.utils.rule.engine.runtime;

import java.util.UUID;

/**
 * DMN models for tests.
 */
public final class TestModels {

  private TestModels() {}

  /** @return a model name that no other test uses */
  public static String uniqueName(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * A discount decision: Gold members get the given discount from a purchase amount of 1000, everyone else 0.
   */
  public static String discount(int goldDiscount) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="_discount" name="discount"
            namespace="https://axonivy.com/dmn/discount"
            expressionLanguage="https://www.omg.org/spec/DMN/20230324/FEEL/"
            typeLanguage="https://www.omg.org/spec/DMN/20230324/FEEL/">
          <inputData id="_in_amount" name="purchaseAmount">
            <variable id="_in_amount_var" name="purchaseAmount" typeRef="number"/>
          </inputData>
          <inputData id="_in_type" name="type">
            <variable id="_in_type_var" name="type" typeRef="string"/>
          </inputData>
          <decision id="_d" name="discount">
            <variable id="_d_var" name="discount" typeRef="number"/>
            <informationRequirement id="_req1">
              <requiredInput href="#_in_amount"/>
            </informationRequirement>
            <informationRequirement id="_req2">
              <requiredInput href="#_in_type"/>
            </informationRequirement>
            <decisionTable id="_t" hitPolicy="FIRST">
              <input id="_ti1">
                <inputExpression id="_tie1" typeRef="number"><text>purchaseAmount</text></inputExpression>
              </input>
              <input id="_ti2">
                <inputExpression id="_tie2" typeRef="string"><text>type</text></inputExpression>
              </input>
              <output id="_to1" typeRef="number"/>
              <rule id="_rule1">
                <inputEntry id="_r1i1"><text>&gt;= 1000</text></inputEntry>
                <inputEntry id="_r1i2"><text>"Gold"</text></inputEntry>
                <outputEntry id="_r1o1"><text>%d</text></outputEntry>
              </rule>
              <rule id="_rule2">
                <inputEntry id="_r2i1"><text>-</text></inputEntry>
                <inputEntry id="_r2i2"><text>-</text></inputEntry>
                <outputEntry id="_r2o1"><text>0</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """.formatted(goldDiscount);
  }

  /**
   * A model with a date input and a date result: the due date is 30 days after the invoice date.
   */
  public static String dueDate() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="_due" name="dueDate"
            namespace="https://axonivy.com/dmn/dueDate"
            expressionLanguage="https://www.omg.org/spec/DMN/20230324/FEEL/"
            typeLanguage="https://www.omg.org/spec/DMN/20230324/FEEL/">
          <inputData id="_in_date" name="invoiceDate">
            <variable id="_in_date_var" name="invoiceDate" typeRef="date"/>
          </inputData>
          <decision id="_d" name="dueDate">
            <variable id="_d_var" name="dueDate" typeRef="date"/>
            <informationRequirement id="_req1">
              <requiredInput href="#_in_date"/>
            </informationRequirement>
            <literalExpression id="_le">
              <text>invoiceDate + duration("P30D")</text>
            </literalExpression>
          </decision>
        </definitions>
        """;
  }

  /** A model that parses as XML but does not compile: the rule has a broken FEEL expression. */
  public static String brokenExpression() {
    return discount(15).replace("&gt;= 1000", "&gt;= )(");
  }

  /** A model that refers to an input that does not exist. */
  public static String missingInput() {
    return discount(15).replace("href=\"#_in_type\"", "href=\"#_unknown\"");
  }

  /**
   * A decision table with many rules for the performance baseline.
   * Rule n matches customers of segment n with an amount in a band of 100.
   */
  public static String decisionTable(int rules) {
    var table = new StringBuilder();
    for (int rule = 0; rule < rules; rule++) {
      int from = rule * 100;
      table.append("      <rule id=\"_rule").append(rule).append("\">")
          .append("<inputEntry id=\"_r").append(rule).append("i1\"><text>[").append(from).append("..")
          .append(from + 100).append(")</text></inputEntry>")
          .append("<inputEntry id=\"_r").append(rule).append("i2\"><text>\"S").append(rule % 50)
          .append("\"</text></inputEntry>")
          .append("<outputEntry id=\"_r").append(rule).append("o1\"><text>").append(rule).append("</text></outputEntry>")
          .append("</rule>\n");
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="_large" name="large"
            namespace="https://axonivy.com/dmn/large"
            expressionLanguage="https://www.omg.org/spec/DMN/20230324/FEEL/"
            typeLanguage="https://www.omg.org/spec/DMN/20230324/FEEL/">
          <inputData id="_in_amount" name="amount">
            <variable id="_in_amount_var" name="amount" typeRef="number"/>
          </inputData>
          <inputData id="_in_segment" name="segment">
            <variable id="_in_segment_var" name="segment" typeRef="string"/>
          </inputData>
          <decision id="_d" name="band">
            <variable id="_d_var" name="band" typeRef="number"/>
            <informationRequirement id="_req1">
              <requiredInput href="#_in_amount"/>
            </informationRequirement>
            <informationRequirement id="_req2">
              <requiredInput href="#_in_segment"/>
            </informationRequirement>
            <decisionTable id="_t" hitPolicy="FIRST">
              <input id="_ti1">
                <inputExpression id="_tie1" typeRef="number"><text>amount</text></inputExpression>
              </input>
              <input id="_ti2">
                <inputExpression id="_tie2" typeRef="string"><text>segment</text></inputExpression>
              </input>
              <output id="_to1" typeRef="number"/>
        %s    </decisionTable>
          </decision>
        </definitions>
        """.formatted(table);
  }
}
