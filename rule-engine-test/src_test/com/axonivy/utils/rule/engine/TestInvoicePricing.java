package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.demo.InvoiceSamples;
import com.axonivy.utils.rule.engine.demo.PricedLine;
import com.axonivy.utils.rule.engine.demo.PricingResult;
import com.axonivy.utils.rule.engine.demo.SalesInvoice;
import com.axonivy.utils.rule.engine.program.internal.RuleErrors;
import com.axonivy.utils.rule.engine.test.mock.MockRuntime;

import ch.ivyteam.ivy.bpm.engine.client.BpmClient;
import ch.ivyteam.ivy.bpm.engine.client.ExecutionResult;
import ch.ivyteam.ivy.bpm.engine.client.element.BpmElement;
import ch.ivyteam.ivy.bpm.engine.client.element.BpmProcess;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import tools.jackson.databind.JsonNode;

/**
 * The callable InvoicePricing of the demo: an invoice with a list of lines goes to the runtime by name, every decision
 * of the model comes back into the field of its name, lists of structures included. The runtime is
 * {@link MockRuntime}, answering what the real runtime answers for the sample invoice.
 */
@IvyProcessTest(enableWebServer = true)
class TestInvoicePricing {

  static final BpmElement PRICE = BpmProcess.name("InvoicePricing").elementName("price(SalesInvoice)");
  static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 19);

  /** What the runtime answers for {@link InvoiceSamples#standard(LocalDate)}. */
  private static final String RESULTS = """
      {"approval":{"route":"Sales manager","reasons":["A line discount of 15% or more"]},
       "orderDiscountPercent":0,
       "pricedLines":[
        {"product":"Laptop 14\\"","category":"Hardware","quantity":10,"unitPrice":1200.00,"gross":12000.00,
         "discountPercent":6,"discountReason":"Hardware: 10 units or more","discounted":11280.00,"orderDiscount":0.00,
         "net":11280.00,"vatRate":8.1,"vat":913.68,"total":12193.68},
        {"product":"Office suite licence, 1 year","category":"Software","quantity":25,"unitPrice":120.00,"gross":3000.00,
         "discountPercent":15,"discountReason":"Volume licence: 20 seats or more","discounted":2550.00,"orderDiscount":0.00,
         "net":2550.00,"vatRate":8.1,"vat":206.55,"total":2756.55},
        {"product":"Installation and setup, hours","category":"Services","quantity":8,"unitPrice":150.00,"gross":1200.00,
         "discountPercent":0,"discountReason":"Services are not discounted","discounted":1200.00,"orderDiscount":0.00,
         "net":1200.00,"vatRate":8.1,"vat":97.20,"total":1297.20},
        {"product":"A4 paper, box of 5 reams","category":"Consumables","quantity":60,"unitPrice":25.00,"gross":1500.00,
         "discountPercent":8,"discountReason":"Consumables: 50 or more","discounted":1380.00,"orderDiscount":0.00,
         "net":1380.00,"vatRate":8.1,"vat":111.78,"total":1491.78},
        {"product":"Project management handbook","category":"Books","quantity":12,"unitPrice":45.00,"gross":540.00,
         "discountPercent":5,"discountReason":"Books: 10 copies or more","discounted":513.00,"orderDiscount":0.00,
         "net":513.00,"vatRate":2.6,"vat":13.34,"total":526.34}],
       "orderDiscounts":[],
       "approvalFlags":[{"approver":"Sales manager","reason":"A line discount of 15% or more"}],
       "shipping":{"fee":0,"reason":"Free delivery in CH from 500"},
       "totals":{"gross":18240.00,"subtotal":16923.00,"lineDiscounts":1317.00,"orderDiscount":0.00,"net":16923.00,
         "vat":1342.55,"shippingFee":0,"total":18265.55}}
      """;

  @BeforeEach
  public void setup(AppFixture fixture) {
    MockRuntime.reset();
    ModelDeployer.reset();
    fixture.var(Var.URL, MockRuntime.url());
    fixture.var(Var.API_KEY, "test-key");
    fixture.var(Var.AUTO_DEPLOY, "true");
    fixture.var(Var.MODEL_PREFIX, "");
  }

  @Test
  void invoiceIsSentByName_andEveryDecisionComesBackToItsField(BpmClient client) {
    MockRuntime.onEvaluate(call -> MockRuntime.results(3, RESULTS));
    SalesInvoice invoice = InvoiceSamples.standard(INVOICE_DATE);
    invoice.setPromoCode(" winter26 ");

    var execution = execute(client, invoice);
    PricingResult pricing = execution.subResult().param("pricing", PricingResult.class);

    assertThat(pricing.getErrorMessage()).isNull();
    var call = MockRuntime.calls("evaluate").get(0);
    assertThat(call.model()).isEqualTo("invoice-pricing");
    assertThat(call.body().has("decision")).as("the whole model is evaluated").isFalse();
    JsonNode input = call.body().get("input");
    assertThat(input.propertyNames()).as("the inputs of dmn/invoice-pricing.dmn, found in in.invoice by name")
        .containsExactlyInAnyOrder("customerTier", "country", "paymentTerms", "promoCode", "expressDelivery",
            "invoiceDate", "lines");
    assertThat(input.get("customerTier").asString()).isEqualTo("Silver");
    assertThat(input.get("promoCode").asString()).as("prepared by the Script before the task").isEqualTo("WINTER26");
    assertThat(input.get("invoiceDate").asString()).as("a LocalDate is a FEEL date").isEqualTo("2026-11-19");
    assertThat(input.get("expressDelivery").asBoolean()).isFalse();
    assertThat(input.get("lines").size()).as("a list of data classes is a list of structures").isEqualTo(5);
    JsonNode laptop = input.get("lines").get(0);
    assertThat(laptop.propertyNames()).containsExactlyInAnyOrder("product", "category", "quantity", "unitPrice");
    assertThat(laptop.get("category").asString()).isEqualTo("Hardware");
    assertThat(laptop.get("quantity").asInt()).isEqualTo(10);
    assertThat(laptop.get("unitPrice").decimalValue()).isEqualByComparingTo("1200");

    assertThat(pricing.getPricedLines()).as("a list of structures fills a list of data classes").hasSize(5);
    PricedLine first = pricing.getPricedLines().get(0);
    assertThat(first.getProduct()).isEqualTo("Laptop 14\"");
    assertThat(first.getQuantity()).isEqualTo(10);
    assertThat(first.getDiscountPercent()).isEqualByComparingTo("6");
    assertThat(first.getDiscountReason()).isEqualTo("Hardware: 10 units or more");
    assertThat(first.getNet()).isEqualByComparingTo("11280");
    assertThat(first.getVatRate()).isEqualByComparingTo("8.1");
    assertThat(first.getVat()).isEqualByComparingTo("913.68");
    assertThat(pricing.getPricedLines().get(4).getVatRate()).isEqualByComparingTo("2.6");
    assertThat(pricing.getOrderDiscounts()).as("COLLECT without a match: an empty list").isEmpty();
    assertThat(pricing.getOrderDiscountPercent()).isEqualByComparingTo("0");
    assertThat(pricing.getShipping().getReason()).isEqualTo("Free delivery in CH from 500");
    assertThat(pricing.getTotals().getVat()).isEqualByComparingTo("1342.55");
    assertThat(pricing.getTotals().getTotal()).isEqualByComparingTo("18265.55");
    assertThat(pricing.getApproval().getRoute()).isEqualTo("Sales manager");
    assertThat(pricing.getApproval().getReasons()).containsExactly("A line discount of 15% or more");
    assertThat(TestBusinessRuleTask.decidedBy(execution, "invoice-pricing")).as("stored on the case").isEqualTo(3);
  }

  @Test
  void orderDiscountsAreAListOfStructures(BpmClient client) {
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, """
        {"orderDiscounts":[{"percent":4,"reason":"Platinum customer bonus"},{"percent":2,"reason":"Early payment: prepaid"},
          {"percent":5,"reason":"Promotion WINTER26"}],
         "orderDiscountPercent":10}"""));

    PricingResult pricing = price(client, InvoiceSamples.standard(INVOICE_DATE));

    assertThat(pricing.getOrderDiscounts()).extracting(discount -> discount.getReason())
        .containsExactly("Platinum customer bonus", "Early payment: prepaid", "Promotion WINTER26");
    assertThat(pricing.getOrderDiscounts().get(2).getPercent()).isEqualByComparingTo("5");
    assertThat(pricing.getOrderDiscountPercent()).as("capped by the model").isEqualByComparingTo("10");
  }

  @Test
  void ruleEngineError_isAnswered_notThrown(BpmClient client) {
    MockRuntime.onEvaluate(call -> MockRuntime.error(422, "EVALUATION_FAILED",
        "Evaluation of model 'invoice-pricing' version 1 failed: the value is not allowed by the declared type"));

    PricingResult pricing = price(client, InvoiceSamples.standard(INVOICE_DATE));

    assertThat(pricing.getErrorCode()).isEqualTo(RuleErrors.DMN);
    assertThat(pricing.getErrorMessage()).contains("not allowed by the declared type");
    assertThat(pricing.getTotals()).isNull();
  }

  static PricingResult price(BpmClient client, SalesInvoice invoice) {
    return execute(client, invoice).subResult().param("pricing", PricingResult.class);
  }

  static ExecutionResult execute(BpmClient client, SalesInvoice invoice) {
    return client.start().subProcess(PRICE).withParam("invoice", invoice).execute();
  }
}
