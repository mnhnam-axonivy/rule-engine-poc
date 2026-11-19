package com.axonivy.utils.rule.engine.demo;

import com.axonivy.utils.rule.engine.demo.InvoicePricingDialog.InvoicePricingDialogData;

/**
 * What the page of the demo shows besides the result of the callable: the version that decided, and the tax
 * formula of that version. In Java, because IvyScript initializes a field it reads: <code>in.pricing.ruleVersion</code>
 * would be 0 instead of not set.
 */
public final class InvoicePricingPage {

  private InvoicePricingPage() {}

  /** After the callable priced the invoice. */
  public static void priced(InvoicePricingDialogData data) {
    PricingResult pricing = data.getPricing();
    if (pricing != null && pricing.getErrorCode() == null) {
      pricing.setRuleVersion(RuleVersion.ofCase(TaxFormula.MODEL));
    }
    Integer version = pricing == null ? null : pricing.getRuleVersion();
    data.setTaxFormula(TaxFormula.of(version));
    data.setTaxSteps(TaxFormula.steps(pricing, data.getTaxFormula()));
    if (data.getTrialFormula() == null || data.getTrialFormula().isBlank()) {
      data.setTrialFormula(data.getTaxFormula());
    }
  }

  /** After the callable priced the invoice: the formula a developer typed in, tried on a copy of the model. */
  public static void tried(InvoicePricingDialogData data) {
    priced(data);
    Integer version = data.getPricing() == null ? null : data.getPricing().getRuleVersion();
    data.setTrial(TaxFormula.tryFormula(data.getInvoice(), version, data.getTrialFormula()));
  }
}
