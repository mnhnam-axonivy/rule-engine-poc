package com.axonivy.utils.rule.engine.demo;

import java.util.ArrayList;

import com.axonivy.utils.rule.engine.demo.InvoicePricingDialog.InvoicePricingDialogData;

import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.scripting.objects.List;

/** In Java, because IvyScript initializes a field it reads: a version that is not set would read as 0. */
public final class InvoicePricingPage {

  private InvoicePricingPage() {}

  public static void priced(InvoicePricingDialogData data) {
    boolean decided = data.getErrorCode() == null || data.getErrorCode().isBlank();
    data.setRuleVersion(decided ? RuleVersion.ofCase(DiscountTable.MODEL) : null);
    data.setPreviousTotal(decided ? data.getLastTotal() : null);
    if (decided) {
      data.setLastTotal(data.getInvoice().getTotal());
    }
    showTable(data, decided);
  }

  private static void showTable(InvoicePricingDialogData data, boolean decided) {
    DiscountTable.Table table;
    try {
      table = DiscountTable.of(data.getRuleVersion());
    } catch (IllegalStateException ex) {
      Ivy.log().warn("Rule engine demo: the table cannot be shown. " + ex.getMessage());
      data.setRules(null);
      return;
    }
    Integer matched = decided ? table.matching(data.getInvoice()) : null;
    var rules = new ArrayList<RuleRow>();
    for (RuleRow rule : table.rules()) {
      rule.setMatched(rule.getNumber().equals(matched));
      rules.add(rule);
    }
    data.setHitPolicy(table.hitPolicy());
    data.setRuleInputs(List.create(String.class, table.inputs()));
    data.setRuleOutputs(List.create(String.class, table.outputs()));
    data.setRules(List.create(RuleRow.class, rules));
  }
}
