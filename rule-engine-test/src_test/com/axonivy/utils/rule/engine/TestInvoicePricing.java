package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.demo.Invoice;
import com.axonivy.utils.rule.engine.demo.InvoiceSamples;
import com.axonivy.utils.rule.engine.program.internal.RuleErrors;
import com.axonivy.utils.rule.engine.test.mock.MockRuntime;
import com.axonivy.utils.rule.engine.test.mock.TestStore;

import ch.ivyteam.ivy.bpm.engine.client.BpmClient;
import ch.ivyteam.ivy.bpm.engine.client.ExecutionResult;
import ch.ivyteam.ivy.bpm.engine.client.element.BpmElement;
import ch.ivyteam.ivy.bpm.engine.client.element.BpmProcess;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import tools.jackson.databind.JsonNode;

/**
 * The callable InvoicePricing of the demo: the lines are summed, the customer tier and the order amount go to the
 * runtime by name, the discount comes back into the invoice by name and is applied. The runtime is
 * {@link MockRuntime}, answering what the real runtime answers for the sample invoice.
 */
@IvyProcessTest(enableWebServer = true)
class TestInvoicePricing {

  static final BpmElement PRICE = BpmProcess.name("InvoicePricing").elementName("price(Invoice)");

  @BeforeEach
  public void setup(AppFixture fixture) {
    MockRuntime.reset();
    TestStore.clear();
    fixture.var(Var.URL, MockRuntime.url());
    fixture.var(Var.API_KEY, "test-key");
    fixture.var(Var.AUTO_DEPLOY, "true");
    fixture.var(Var.MODEL_PREFIX, "");
  }

  @Test
  void tierAndOrderAmountAreSentByName_andTheDiscountIsApplied(BpmClient client) {
    MockRuntime.onEvaluate(call -> MockRuntime.results(1,
        "{\"discount\":{\"discountPercent\":2,\"discountReason\":\"Silver customer\"}}"));

    var execution = execute(client, InvoiceSamples.standard());
    Invoice invoice = execution.subResult().param("invoice", Invoice.class);

    assertThat(execution.subResult().param("errorMessage", String.class)).isNullOrEmpty();
    var call = MockRuntime.calls("evaluate").get(0);
    assertThat(call.model()).isEqualTo("invoice-discount");
    assertThat(call.body().get("decision").asString()).isEqualTo("discount");
    JsonNode input = call.body().get("input");
    assertThat(input.propertyNames()).as("the inputs of dmn/invoice-discount.dmn, found in in.invoice by name")
        .containsExactlyInAnyOrder("customerTier", "orderAmount");
    assertThat(input.get("customerTier").asString()).isEqualTo("Silver");
    assertThat(input.get("orderAmount").decimalValue()).as("summed by the Script before the task").isEqualByComparingTo("9200");

    assertThat(invoice.getLines()).extracting(line -> line.getAmount().toPlainString())
        .containsExactly("4800.00", "1280.00", "720.00", "1200.00", "1200.00");
    assertThat(invoice.getDiscountPercent()).as("an entry of the result, written to the field of its name").isEqualByComparingTo("2");
    assertThat(invoice.getDiscountReason()).isEqualTo("Silver customer");
    assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("184.00");
    assertThat(invoice.getTotal()).isEqualByComparingTo("9016.00");
    assertThat(TestBusinessRuleTask.decidedBy(execution, "invoice-discount")).as("stored on the case").isEqualTo(1);
  }

  @Test
  void discountIsRoundedToTheCent(BpmClient client) {
    MockRuntime.onEvaluate(call -> MockRuntime.results(1,
        "{\"discount\":{\"discountPercent\":2.5,\"discountReason\":\"Odd\"}}"));
    Invoice sample = InvoiceSamples.standard();
    sample.getLines().get(0).setQuantity(1);
    sample.getLines().get(0).setUnitPrice(new java.math.BigDecimal("0.30"));

    Invoice invoice = price(client, sample);

    assertThat(invoice.getOrderAmount()).isEqualByComparingTo("4400.30");
    assertThat(invoice.getDiscountAmount()).as("110.0075, half up").isEqualByComparingTo("110.01");
    assertThat(invoice.getTotal()).isEqualByComparingTo("4290.29");
  }

  @Test
  void ruleEngineError_isAnswered_notThrown(BpmClient client) {
    MockRuntime.onEvaluate(call -> MockRuntime.error(422, "EVALUATION_FAILED",
        "Evaluation of model 'invoice-discount' version 1 failed: no rule matched"));

    var result = execute(client, InvoiceSamples.standard()).subResult();

    assertThat(result.param("errorCode", String.class)).isEqualTo(RuleErrors.DMN);
    assertThat(result.param("errorMessage", String.class)).contains("no rule matched");
    assertThat(result.param("invoice", Invoice.class).getTotal()).isNull();
  }

  static Invoice price(BpmClient client, Invoice invoice) {
    return execute(client, invoice).subResult().param("invoice", Invoice.class);
  }

  static ExecutionResult execute(BpmClient client, Invoice invoice) {
    return client.start().subProcess(PRICE).withParam("invoice", invoice).execute();
  }
}
