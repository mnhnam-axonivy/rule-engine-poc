package com.axonivy.utils.rule.engine.demo;

import java.math.BigDecimal;

import ch.ivyteam.ivy.scripting.objects.List;

/**
 * The invoice the demo starts with: a Silver customer, an order of 9,200. A fifth laptop brings it to 10,400, where
 * another rule of the table decides.
 */
public final class InvoiceSamples {

  private InvoiceSamples() {}

  public static Invoice standard() {
    var invoice = new Invoice();
    invoice.setNumber("INV-2026-0042");
    invoice.setCustomer("Bluebird Architects");
    invoice.setCustomerTier("Silver");
    invoice.setLines(List.create(InvoiceLine.class, java.util.List.of(
        line("Laptop 14\"", 4, "1200.00"),
        line("Monitor 27\"", 4, "320.00"),
        line("Docking station", 4, "180.00"),
        line("Office suite licence, 1 year", 10, "120.00"),
        line("Installation and setup, hours", 8, "150.00"))));
    return invoice;
  }

  private static InvoiceLine line(String product, int quantity, String unitPrice) {
    var line = new InvoiceLine();
    line.setProduct(product);
    line.setQuantity(quantity);
    line.setUnitPrice(new BigDecimal(unitPrice));
    return line;
  }
}
