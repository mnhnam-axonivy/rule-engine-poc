package com.axonivy.utils.rule.engine.demo;

import java.math.BigDecimal;
import java.time.LocalDate;

import ch.ivyteam.ivy.scripting.objects.List;

/**
 * The invoice the demo starts with. Each of its five lines is decided by another rule of the table
 * lineDiscountTable, so changing a line, the customer tier or the country shows a rule at work.
 */
public final class InvoiceSamples {

  private InvoiceSamples() {}

  /** @return the sample invoice, dated today */
  public static SalesInvoice standard() {
    return standard(LocalDate.now());
  }

  public static SalesInvoice standard(LocalDate invoiceDate) {
    var invoice = new SalesInvoice();
    invoice.setNumber("INV-2026-0042");
    invoice.setCustomer("Bluebird Architects");
    invoice.setCustomerTier("Silver");
    invoice.setCountry("CH");
    invoice.setPaymentTerms("Net 30");
    invoice.setPromoCode("");
    invoice.setExpressDelivery(false);
    invoice.setInvoiceDate(invoiceDate);
    invoice.setLines(List.create(InvoiceLine.class, java.util.List.of(
        line("Laptop 14\"", "Hardware", 10, "1200.00"),
        line("Office suite licence, 1 year", "Software", 25, "120.00"),
        line("Installation and setup, hours", "Services", 8, "150.00"),
        line("A4 paper, box of 5 reams", "Consumables", 60, "25.00"),
        line("Project management handbook", "Books", 12, "45.00"))));
    return invoice;
  }

  private static InvoiceLine line(String product, String category, int quantity, String unitPrice) {
    var line = new InvoiceLine();
    line.setProduct(product);
    line.setCategory(category);
    line.setQuantity(quantity);
    line.setUnitPrice(new BigDecimal(unitPrice));
    return line;
  }
}
