package com.dqs.api.service;

import com.dqs.api.dto.QuotationItemResponse;
import com.dqs.api.dto.QuotationResponse;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotePdfService {

    private final QuotationService quotationService;
    private final FiscalService    fiscalService;
    private final DeliveryService  deliveryService;
    private final JdbcTemplate     jdbcTemplate;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final String[] FEL_COUNTRIES = {"SLV", "SV", "CR", "CRC"};

    // ── Public entry point ────────────────────────────────────────────────────

    public byte[] generate(Long quotationId) throws Exception {
        log.info("[QuotePdfService] generate quotationId={}", quotationId);

        // 1. Collect data
        QuotationResponse quote = quotationService.getById(quotationId);
        List<QuotationItemResponse> allItems = quotationService.getItems(quotationId);
        Map<String, Object> store   = getStore(quote.getStoreId());
        Map<String, Object> fiscal  = fiscalService.getFiscalDataByQuotation(quotationId);
        Map<String, Object> delivery = deliveryService.getDelivery(quotationId);

        String currency = str(store, "moneda");
        String country  = str(store, "pais_iso2");
        boolean hasFel  = isFelCountry(country);

        // 2. Filter delivery item from regular items
        List<QuotationItemResponse> items = allItems.stream()
            .filter(i -> !DeliveryService.DELIVERY_PRODUCT_ID.equals(i.getProductId()))
            .toList();

        // 3. Totals
        boolean isColombia = quote.getStoreId() != null && quote.getStoreId() >= 6100 && quote.getStoreId() <= 6199;
        BigDecimal subtotal = items.stream()
            .map(i -> i.getAmount() != null ? i.getAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalTax = items.stream()
            .map(i -> i.getTaxAmount() != null ? i.getTaxAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalIco = isColombia ? items.stream()
            .map(i -> {
                BigDecimal ico = i.getTaxIco() != null ? i.getTaxIco() : BigDecimal.ZERO;
                BigDecimal qty = i.getQty() != null ? i.getQty() : BigDecimal.ONE;
                return ico.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add) : BigDecimal.ZERO;
        BigDecimal deliveryAmt = delivery != null && delivery.get("amount") != null
            ? new BigDecimal(delivery.get("amount").toString()) : BigDecimal.ZERO;
        BigDecimal coBase = isColombia ? subtotal.subtract(totalTax).subtract(totalIco) : BigDecimal.ZERO;
        BigDecimal total = isColombia
            ? coBase.add(totalTax).add(totalIco).add(deliveryAmt)
            : subtotal.add(totalTax).add(deliveryAmt);

        // 4. Build HTML
        String html = buildHtml(quote, items, store, fiscal, delivery,
            currency, hasFel, isColombia, subtotal, totalTax, totalIco, coBase, deliveryAmt, total);

        // 5. Render to PDF
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, null);
        builder.toStream(out);
        builder.run();

        log.info("[QuotePdfService] PDF generated {} bytes for quotationId={}", out.size(), quotationId);
        return out.toByteArray();
    }

    // ── HTML template ─────────────────────────────────────────────────────────

    private String buildHtml(
            QuotationResponse quote,
            List<QuotationItemResponse> items,
            Map<String, Object> store,
            Map<String, Object> fiscal,
            Map<String, Object> delivery,
            String currency,
            boolean hasFel,
            boolean isColombia,
            BigDecimal subtotal, BigDecimal totalTax, BigDecimal totalIco,
            BigDecimal coBase, BigDecimal deliveryAmt, BigDecimal total) {

        StringBuilder sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: Arial, Helvetica, sans-serif; font-size: 11px; color: #222; padding: 28px 32px; }
                h1 { font-size: 20px; font-weight: bold; color: #003087; }
                h2 { font-size: 11px; text-transform: uppercase; letter-spacing: 0.07em; color: #666; margin-bottom: 6px; }
                .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; border-bottom: 2px solid #003087; padding-bottom: 12px; }
                .header-left h1 { margin-bottom: 2px; }
                .header-right { text-align: right; font-size: 10px; color: #555; }
                .header-right .quote-num { font-size: 16px; font-weight: bold; color: #003087; }
                .grid2 { display: flex; gap: 16px; margin-bottom: 14px; }
                .grid2 > div { flex: 1; }
                .section { background: #f8f9fb; border: 1px solid #e0e4ea; border-radius: 6px; padding: 10px 14px; margin-bottom: 12px; }
                .section h2 { margin-bottom: 6px; }
                .row { width: 100%; border-bottom: 1px solid #eef0f4; }
                .row:last-child { border-bottom: none; }
                .row td { padding: 3px 4px; }
                .row td.label { color: #888; width: 130px; white-space: nowrap; }
                .row td.val { font-weight: 600; color: #222; }
                table { width: 100%; border-collapse: collapse; margin-bottom: 12px; font-size: 10px; }
                thead tr { background: #003087; color: #fff; }
                thead th { padding: 6px 8px; text-align: left; }
                thead th.num { text-align: right; }
                tbody tr:nth-child(even) { background: #f4f6fb; }
                tbody td { padding: 5px 8px; vertical-align: top; border-bottom: 1px solid #e8eaf0; }
                tbody td.num { text-align: right; }
                .totals { border: 1px solid #e0e4ea; border-radius: 6px; padding: 10px 14px; margin-bottom: 14px; }
                .totals .row { padding: 3px 0; }
                .totals .total-line { border-top: 1.5px solid #222; margin-top: 6px; padding-top: 6px; font-size: 13px; font-weight: bold; }
                .gallery { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px; }
                .gallery-item { width: 22%; border: 1px solid #e0e4ea; border-radius: 4px; padding: 6px; text-align: center; page-break-inside: avoid; }
                .gallery-item img { max-width: 100%; max-height: 90px; object-fit: contain; display: block; margin: 4px auto; }
                .gallery-item p { font-size: 8px; color: #555; margin-top: 3px; }
                .footer { margin-top: 20px; padding-top: 10px; border-top: 1px solid #e0e4ea; font-size: 8px; color: #999; text-align: center; }
                .badge-delivery { color: #1d6fcf; font-weight: 600; }
                .page-break { page-break-before: always; }
              </style>
            </head>
            <body>
            """);

        // ── Header ──
        sb.append("<div class=\"header\">");
        sb.append("<div class=\"header-left\"><h1>PriceSmart</h1><div style=\"font-size:12px;color:#555;\">Business to Business Quote</div></div>");
        sb.append("<div class=\"header-right\">");
        sb.append("<div class=\"quote-num\">Quote #").append(quote.getId()).append("</div>");
        if (quote.getQuoteNo() != null) sb.append("<div>").append(esc(quote.getQuoteNo())).append("</div>");
        if (quote.getDateTime() != null) sb.append("<div>Date: ").append(quote.getDateTime().format(DATE_FMT)).append("</div>");
        if (quote.getDexpired() != null) sb.append("<div>Expires: ").append(quote.getDexpired().format(DATE_FMT)).append("</div>");
        sb.append("</div></div>");

        // ── Club + Customer (side by side) ──
        sb.append("<div class=\"grid2\">");

        // Club
        sb.append("<div class=\"section\"><h2>Club</h2>");
        row(sb, "Name",     str(store, "nombre"));
        row(sb, "Club ID",  str(store, "ps_tienda_id"));
        row(sb, "Country",  str(store, "pais_iso2"));
        row(sb, "Currency", currency);
        sb.append("</div>");

        // Customer
        sb.append("<div class=\"section\"><h2>Customer</h2>");
        row(sb, "Name",       quote.getCustomerName());
        row(sb, "Business",   quote.getCustomerBusiness());
        row(sb, "Membership", quote.getCustomerMembership());
        sb.append("</div>");

        sb.append("</div>"); // grid2

        // ── FEL ──
        if (hasFel && fiscal != null) {
            sb.append("<div class=\"section\"><h2>Electronic Invoice (FEL)</h2>");
            rowObj(sb, "NRC",               fiscal.get("nrc"));
            rowObj(sb, "Economic Activity", fiscal.getOrDefault("economic_activity_name", fiscal.get("economic_activity_code")));
            rowObj(sb, "City",              fiscal.getOrDefault("city_name", fiscal.get("city_code")));
            rowObj(sb, "Zone",              fiscal.getOrDefault("zone_name", fiscal.get("zone_code")));
            rowObj(sb, "Neighborhood",      fiscal.getOrDefault("neighborhood_name", fiscal.get("neighborhood_code")));
            sb.append("</div>");
        }

        // ── Delivery ──
        if (delivery != null) {
            sb.append("<div class=\"section\"><h2>Delivery</h2>");
            rowObj(sb, "Amount",  currency + " " + fmt(deliveryAmt));
            rowObj(sb, "Address", delivery.get("address"));
            rowObj(sb, "Date",    delivery.get("delivery_date"));
            Object hf = delivery.get("hour_from"), ht = delivery.get("hour_to");
            if (hf != null) rowObj(sb, "Time window", hf + ":00 - " + ht + ":00");
            rowObj(sb, "Ring", delivery.get("ring"));
            rowObj(sb, "Box",  delivery.get("box"));
            sb.append("</div>");
        }

        // ── Items table ──
        sb.append("<h2 style=\"margin-bottom:6px;\">Items</h2>");
        sb.append("<table><thead><tr>");
        sb.append("<th>#</th><th>Code</th><th>Description</th><th>Dept / Category</th>");
        sb.append("<th class=\"num\">Quantity</th><th class=\"num\">Unit Price</th>");
        sb.append("<th class=\"num\">Amount</th><th class=\"num\">Tax</th>");
        sb.append("</tr></thead><tbody>");

        int idx = 1;
        for (QuotationItemResponse item : items) {
            BigDecimal amt = item.getAmount() != null ? item.getAmount() : BigDecimal.ZERO;
            BigDecimal tax = item.getTaxAmount() != null ? item.getTaxAmount() : BigDecimal.ZERO;
            String dept = (item.getDepartment() != null ? item.getDepartment() : "")
                + (item.getCategory() != null && !item.getCategory().isBlank() ? " / " + item.getCategory() : "");
            sb.append("<tr>");
            sb.append("<td>").append(idx++).append("</td>");
            sb.append("<td>").append(esc(item.getProductId())).append("</td>");
            sb.append("<td>").append(esc(item.getDescription())).append("</td>");
            sb.append("<td>").append(esc(dept)).append("</td>");
            sb.append("<td class=\"num\">").append(item.getQty() != null ? item.getQty().stripTrailingZeros().toPlainString() : "").append("</td>");
            sb.append("<td class=\"num\">").append(currency).append(" ").append(fmt(item.getRate())).append("</td>");
            sb.append("<td class=\"num\">").append(currency).append(" ").append(fmt(amt)).append("</td>");
            sb.append("<td class=\"num\">").append(tax.compareTo(BigDecimal.ZERO) > 0 ? fmt(tax) : "—").append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");

        // ── Totals ──
        sb.append("<div class=\"totals\">");
        if (isColombia) {
            rowAmt(sb, "Total Valor Base", currency, coBase, false);
            if (totalTax.compareTo(BigDecimal.ZERO) > 0) rowAmt(sb, "Impuesto", currency, totalTax, false);
            if (totalIco.compareTo(BigDecimal.ZERO) > 0) rowAmt(sb, "Impuesto ICO LICOR o IBUA", currency, totalIco, false);
            if (deliveryAmt.compareTo(BigDecimal.ZERO) > 0) rowAmt(sb, "Delivery", currency, deliveryAmt, false);
            rowAmt(sb, "Compra Total", currency, total, true);
        } else {
            rowAmt(sb, "Subtotal", currency, subtotal, false);
            if (totalTax.compareTo(BigDecimal.ZERO) > 0) rowAmt(sb, "Tax", currency, totalTax, false);
            if (deliveryAmt.compareTo(BigDecimal.ZERO) > 0) rowAmt(sb, "Delivery", currency, deliveryAmt, false);
            rowAmt(sb, "Total", currency, total, true);
        }
        sb.append("<div style=\"font-size:9px;color:#999;text-align:right;margin-top:4px;\">")
          .append(items.size()).append(" item").append(items.size() != 1 ? "s" : "")
          .append(delivery != null ? " + delivery" : "").append("</div>");
        sb.append("</div>");

        // ── Gallery ──
        List<QuotationItemResponse> withImages = items.stream()
            .filter(i -> i.getPicture1() != null && !i.getPicture1().isBlank()
                      && !i.getPicture1().equalsIgnoreCase("NULL")
                      && (i.getIncludepic() == null || i.getIncludepic() == 1))
            .toList();

        if (!withImages.isEmpty()) {
            sb.append("<div class=\"page-break\"></div>");
            sb.append("<h2 style=\"margin-bottom:8px;\">Product Images</h2>");
            sb.append("<div class=\"gallery\">");
            for (QuotationItemResponse item : withImages) {
                sb.append("<div class=\"gallery-item\">");
                sb.append("<p style=\"font-weight:600;\">").append(esc(item.getProductId())).append("</p>");
                sb.append("<img src=\"").append(esc(item.getPicture1())).append("\"/>");
                sb.append("<p>").append(esc(item.getDescription())).append("</p>");
                sb.append("</div>");
            }
            sb.append("</div>");
        }

        // ── Footer ──
        sb.append("<div class=\"footer\">PriceSmart B2B &#8212; This quote is valid until ")
          .append(quote.getDexpired() != null ? quote.getDexpired().format(DATE_FMT) : "N/A")
          .append(". Prices subject to change without notice.</div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> getStore(Integer storeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT ps_tienda_id, nombre, pais_iso2, moneda FROM ps_tienda WHERE ps_tienda_id = ?", storeId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private boolean isFelCountry(String iso) {
        if (iso == null) return false;
        for (String c : FEL_COUNTRIES) if (c.equalsIgnoreCase(iso)) return true;
        return false;
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private String fmt(BigDecimal n) {
        if (n == null) return "0.00";
        return String.format("%,.2f", n);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void row(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("<p style=\"margin:3px 0; border-bottom:1px solid #eef0f4; padding-bottom:3px;\">")
          .append("<span style=\"color:#888;\">").append(esc(label)).append(": </span>")
          .append("<span style=\"font-weight:bold; color:#222;\">").append(esc(value)).append("</span>")
          .append("</p>");
    }

    private void rowObj(StringBuilder sb, String label, Object value) {
        if (value == null) return;
        row(sb, label, value.toString());
    }

    private void rowAmt(StringBuilder sb, String label, String currency, BigDecimal amount, boolean bold) {
        String pStyle = bold
            ? "margin:6px 0 2px 0; padding-top:6px; border-top:2px solid #222; font-size:13px; font-weight:bold; display:block;"
            : "margin:3px 0; border-bottom:1px solid #eef0f4; padding-bottom:3px; display:block;";
        String lblColor = bold ? "#222" : "#888";
        String valWeight = bold ? "bold" : "600";
        sb.append("<p style=\"").append(pStyle).append("\">")
          .append("<span style=\"color:").append(lblColor).append(";\">").append(label).append(": </span>")
          .append("<span style=\"font-weight:").append(valWeight).append("; color:#222;\">").append(currency).append(" ").append(fmt(amount)).append("</span>")
          .append("</p>");
    }
}
