package com.dqs.api.service;

import com.itextpdf.html2pdf.HtmlConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryLogPdfService {

    private final com.dqs.api.repository.DeliveryLogRepository deliveryLogRepository;

    public byte[] generatePdf(long logId) throws Exception {
        log.info("[DeliveryLogPdfService] generatePdf logId={}", logId);

        Map<String, Object> logData = deliveryLogRepository.findById(logId);
        List<Map<String, Object>> deliveries = deliveryLogRepository.findDeliveriesByLogId(logId);

        if (logData == null || deliveries.isEmpty()) {
            throw new IllegalArgumentException("Log or deliveries not found: " + logId);
        }

        String html = buildHtml(logData, deliveries);
        byte[] pdfBytes = convertHtmlToPdf(html);

        log.info("[DeliveryLogPdfService] generatePdf OK logId={} size={} bytes", logId, pdfBytes.length);
        return pdfBytes;
    }

    private byte[] convertHtmlToPdf(String html) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HtmlConverter.convertToPdf(html, baos);
        return baos.toByteArray();
    }

    private String buildHtml(Map<String, Object> log, List<Map<String, Object>> deliveries) {
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<meta charset='UTF-8'>\n");
        html.append("<title>Delivery Log #").append(log.get("logcargueid")).append("</title>\n");
        html.append("<style>\n");
        html.append("  * { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("  body { font-family: Arial, sans-serif; font-size: 11px; padding: 20px; line-height: 1.4; }\n");
        html.append("  .header { text-align: center; margin-bottom: 20px; border-bottom: 3px solid #0066cc; padding-bottom: 15px; }\n");
        html.append("  .header h1 { color: #0066cc; margin: 0 0 5px 0; font-size: 18px; font-weight: bold; }\n");
        html.append("  .header p { color: #666; margin: 0; font-size: 10px; }\n");
        html.append("  .info-grid { display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; gap: 15px; margin-bottom: 20px; }\n");
        html.append("  .info-box { border: 1px solid #ccc; padding: 10px; background: #f9f9f9; }\n");
        html.append("  .info-label { font-weight: bold; color: #0066cc; font-size: 10px; margin-bottom: 3px; }\n");
        html.append("  .info-value { color: #333; font-size: 11px; }\n");
        html.append("  table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }\n");
        html.append("  th { background-color: #0066cc; color: white; padding: 8px 5px; text-align: left; font-size: 10px; font-weight: bold; border: 1px solid #004399; }\n");
        html.append("  td { padding: 6px 5px; border: 1px solid #ddd; font-size: 10px; }\n");
        html.append("  tr:nth-child(even) { background-color: #f5f5f5; }\n");
        html.append("  .total-row { font-weight: bold; background-color: #e6f0ff; border-top: 2px solid #0066cc; }\n");
        html.append("  .amount { text-align: right; }\n");
        html.append("  .number { text-align: center; }\n");
        html.append("  .signature-section { margin-top: 30px; display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 30px; }\n");
        html.append("  .signature { text-align: center; }\n");
        html.append("  .signature-line { border-top: 1px solid black; margin-bottom: 5px; height: 50px; }\n");
        html.append("  .signature-label { font-size: 9px; color: #333; font-weight: bold; }\n");
        html.append("</style>\n");
        html.append("</head>\n<body>\n");

        html.append("<div class='header'>\n");
        html.append("  <h1>DELIVERY LOG #").append(log.get("logcargueid")).append("</h1>\n");
        html.append("  <p>Log de Cargue / Envío Report</p>\n");
        html.append("</div>\n");

        html.append("<div class='info-grid'>\n");
        html.append("  <div class='info-box'>\n");
        html.append("    <div class='info-label'>LOG ID</div>\n");
        html.append("    <div class='info-value'>").append(log.get("logcargueid")).append("</div>\n");
        html.append("  </div>\n");
        html.append("  <div class='info-box'>\n");
        html.append("    <div class='info-label'>CREATED</div>\n");
        html.append("    <div class='info-value'>").append(log.get("fecha")).append(" ").append(log.get("hora")).append("</div>\n");
        html.append("  </div>\n");
        html.append("  <div class='info-box'>\n");
        html.append("    <div class='info-label'>ROUTE</div>\n");
        html.append("    <div class='info-value'>").append(log.get("delivery_ruta")).append("</div>\n");
        html.append("  </div>\n");
        html.append("  <div class='info-box'>\n");
        html.append("    <div class='info-label'>STATUS</div>\n");
        html.append("    <div class='info-value'>").append(statusLabel(toInt(log.get("statusid")))).append("</div>\n");
        html.append("  </div>\n");
        html.append("</div>\n");

        html.append("<table>\n");
        html.append("  <thead>\n");
        html.append("    <tr>\n");
        html.append("      <th class='number'>#</th>\n");
        html.append("      <th>Quote ID</th>\n");
        html.append("      <th>Customer</th>\n");
        html.append("      <th>Date</th>\n");
        html.append("      <th>Hours</th>\n");
        html.append("      <th>Address</th>\n");
        html.append("      <th class='amount'>Amount</th>\n");
        html.append("      <th class='number'>Pallets</th>\n");
        html.append("    </tr>\n");
        html.append("  </thead>\n");
        html.append("  <tbody>\n");

        double totalAmount = 0;
        int index = 1;
        for (Map<String, Object> d : deliveries) {
            double amount = toDouble(d.get("amount"));
            totalAmount += amount;

            html.append("    <tr>\n");
            html.append("      <td class='number'>").append(index).append("</td>\n");
            html.append("      <td>#").append(d.get("quotationId")).append("</td>\n");
            html.append("      <td>").append(d.get("customerName")).append("</td>\n");
            html.append("      <td>").append(d.get("deliveryDate")).append("</td>\n");
            html.append("      <td>").append(d.get("hourFrom")).append(":00-").append(d.get("hourTo")).append(":00</td>\n");
            html.append("      <td>").append(d.get("address")).append("</td>\n");
            html.append("      <td class='amount'>").append(String.format("%.2f", amount)).append("</td>\n");
            html.append("      <td class='number'>").append(d.get("pallets")).append("</td>\n");
            html.append("    </tr>\n");

            index++;
        }

        html.append("    <tr class='total-row'>\n");
        html.append("      <td colspan='6' class='amount'><strong>TOTAL:</strong></td>\n");
        html.append("      <td class='amount'><strong>").append(String.format("%.2f", totalAmount)).append("</strong></td>\n");
        html.append("      <td></td>\n");
        html.append("    </tr>\n");
        html.append("  </tbody>\n");
        html.append("</table>\n");

        html.append("<div class='signature-section'>\n");
        html.append("  <div class='signature'>\n");
        html.append("    <div class='signature-line'></div>\n");
        html.append("    <div class='signature-label'>DRIVER SIGNATURE</div>\n");
        html.append("  </div>\n");
        html.append("  <div class='signature'>\n");
        html.append("    <div class='signature-line'></div>\n");
        html.append("    <div class='signature-label'>RECEIVER SIGNATURE</div>\n");
        html.append("  </div>\n");
        html.append("  <div class='signature'>\n");
        html.append("    <div class='signature-line'></div>\n");
        html.append("    <div class='signature-label'>DATE</div>\n");
        html.append("  </div>\n");
        html.append("</div>\n");

        html.append("</body>\n</html>");

        return html.toString();
    }

    private String statusLabel(int status) {
        return switch (status) {
            case 2 -> "ACTIVE";
            case 3 -> "SENT";
            default -> "UNKNOWN";
        };
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(v.toString());
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }
}
