package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.demo.DiscountTable;
import com.axonivy.utils.rule.engine.demo.Invoice;
import com.axonivy.utils.rule.engine.demo.RuleRow;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.IvyTest;

@IvyTest
class TestDiscountTable {

  @Test
  void tableOfTheProjectFile() {
    DiscountTable.Table table = DiscountTable.of(null);

    assertThat(table.hitPolicy()).isEqualTo("FIRST");
    assertThat(table.inputs()).containsExactly("customerTier", "orderAmount");
    assertThat(table.outputs()).containsExactly("discountPercent", "discountReason");
    assertThat(table.rules()).extracting(RuleRow::getNumber).containsExactly(1, 2, 3, 4, 5, 6, 7);
    RuleRow silver = table.rules().get(5);
    assertThat(silver.getInputs()).containsExactly("\"Silver\"", "-");
    assertThat(silver.getOutputs()).containsExactly("2", "\"Silver customer\"");
    assertThat(table.rules().get(4).getInputs()).as("unescaped").containsExactly("\"Silver\"", ">= 10000");
  }

  @Test
  void ruleThatDecided_isTheFirstWithTheResult() {
    DiscountTable.Table table = DiscountTable.of(null);

    assertThat(table.matching(result("2", "Silver customer"))).isEqualTo(6);
    assertThat(table.matching(result("8", "Gold, order of 10,000 or more"))).isEqualTo(3);
    assertThat(table.matching(result("8.00", "Platinum customer"))).as("the same number").isEqualTo(2);
    assertThat(table.matching(result("0", "No discount"))).isEqualTo(7);
    assertThat(table.matching(result("3", "Silver customer"))).as("no rule gives this result").isNull();
  }

  @Test
  void columnAddedByARuleOwner() throws IOException {
    String keyAccount = Files.readString(Project.current().directory()
        .resolve("src_test/com/axonivy/utils/rule/engine/scenario/invoice-discount-key-account.dmn.xml"));

    DiscountTable.Table table = DiscountTable.read(keyAccount);

    assertThat(table.inputs()).containsExactly("customerTier", "orderAmount", "customer");
    assertThat(table.rules()).hasSize(8);
    assertThat(table.rules().get(0).getInputs()).containsExactly("-", "-", "\"Bluebird Architects\"");
    assertThat(table.matching(result("10", "Key account: framework contract"))).isEqualTo(1);
    assertThatThrownBy(() -> DiscountTable.read("<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\"/>"))
        .hasMessageContaining("no decision table 'discount'");
  }

  private static Invoice result(String percent, String reason) {
    var invoice = new Invoice();
    invoice.setDiscountPercent(new BigDecimal(percent));
    invoice.setDiscountReason(reason);
    return invoice;
  }
}
