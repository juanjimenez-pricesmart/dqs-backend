package com.dqs.api.service;

import com.dqs.api.dto.QuotationItemResponse;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.repository.support.NativeQueries;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Callejas picking sheet — legacy's Orders::reportecallejas.
 *
 * One card per line: the product code, the Callejas description, the quantity,
 * and a Code 128 barcode the warehouse scans. Legacy renders it as HTML with
 * `onLoad="printpage()"` and asks the browser to print it; we produce a PDF,
 * as we do for the quotation itself, so the document is the same however it
 * was obtained.
 *
 * Three legacy behaviours are kept deliberately.
 *
 * The lines are ordered by the first two characters of the product code — the
 * same grouping QuoteItemSort applies to a Callejas quotation, and for the same
 * reason: it is the order the shelves are in.
 *
 * The join to ps_callejas_items is an INNER one, so a line whose code is not a
 * Callejas item does not appear on the sheet at all. That is legacy's
 * behaviour and it is load-bearing — the sheet describes what Callejas ordered,
 * not what the quotation happens to carry — but it does mean a manually added
 * line goes silently missing, which is worth knowing when one is queried.
 *
 * And the barcode is printed from `cbarras` exactly as stored. That column is a
 * `double`, so any barcode with a leading zero has already lost it: 41449003153
 * is eleven digits where a UPC-A is twelve. Legacy prints the same wrong number.
 * Padding it here would mean guessing the intended length, which is a question
 * about the data rather than about this code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallejasReportService {

    private final QuotationService quotationService;
    private final NativeQueries nativeQueries;

    private static final int BARCODE_WIDTH = 300;
    private static final int BARCODE_HEIGHT = 70;

    /** Legacy's getOrdersDataCallejas: the header is keyed by club and order number. */
    private static final String HEADER_SQL = """
        SELECT ordendecompra, fecha, fecha_entrega, sucursalid, cantidad
          FROM ps_callejas_header
         WHERE ps_tienda_id = ? AND ordendecompra = ?
        """;

    private static final String SUCURSAL_SQL =
        "SELECT sucursalid, nombre FROM ps_callejas_sucursales WHERE sucursalid = ?";

    private static final String CLUB_SQL =
        "SELECT ps_tienda_id, nombre, direccion, telefono FROM ps_tienda WHERE ps_tienda_id = ?";

    /** Legacy's getOrdersItemDataCallejas, minus the order_id join we do in memory. */
    private static final String CALLEJAS_ITEMS_SQL = """
        SELECT item, cbarras, SUBSTRING(descripcion_callejas, 1, 30) AS descallejas
          FROM ps_callejas_items
        """;

    // ── Public entry point ────────────────────────────────────────────────────

    public byte[] generate(Long quotationId) throws Exception {
        QuotationResponse quote = quotationService.getById(quotationId);
        if (quote.getOdc() == null || quote.getOdc() == 0L) {
            throw new QuotationNotFoundException(quotationId);
        }

        int storeId = quote.getStoreId() != null ? quote.getStoreId() : 0;
        Map<String, Object> club = first(CLUB_SQL, storeId);
        Map<String, Object> header = first(HEADER_SQL, storeId, quote.getOdc());
        Map<String, Object> sucursal = header.get("sucursalid") == null
                ? Map.of() : first(SUCURSAL_SQL, header.get("sucursalid"));

        // One lookup for the whole catalog rather than a join our schema cannot
        // make: quotation_items is ours and ps_callejas_items is theirs, in a
        // different collation, and the product code is a string on one side and
        // a double on the other.
        Map<String, Map<String, Object>> callejasItems = nativeQueries.list(CALLEJAS_ITEMS_SQL).stream()
                .filter(row -> row.get("item") != null)
                .collect(java.util.stream.Collectors.toMap(
                        row -> plainNumber(row.get("item")),
                        row -> row,
                        (a, b) -> a));

        List<Card> cards = quotationService.getItems(quotationId).stream()
                .filter(i -> com.dqs.api.util.SpecialItems.isRegularLine(i.getProductId()))
                .map(i -> toCard(i, callejasItems.get(i.getProductId())))
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing(Card::prefix))
                .toList();

        log.info("[CallejasReportService] quotationId={} odc={} lines={} cards={}",
                quotationId, quote.getOdc(), quotationService.getItems(quotationId).size(), cards.size());

        String html = buildHtml(quote, club, header, sucursal, cards);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, null);
        builder.toStream(out);
        builder.run();
        return out.toByteArray();
    }

    // ── Cards ─────────────────────────────────────────────────────────────────

    private record Card(String productId, String description, String qty, String barcode) {
        String prefix() {
            return productId.length() <= 2 ? productId : productId.substring(0, 2);
        }
    }

    /** Null when the code is not a Callejas item — legacy's inner join drops it. */
    private Card toCard(QuotationItemResponse item, Map<String, Object> callejas) {
        if (callejas == null) return null;
        return new Card(
                item.getProductId() == null ? "" : item.getProductId(),
                str(callejas.get("descallejas")),
                item.getQty() == null ? "" : item.getQty().stripTrailingZeros().toPlainString(),
                plainNumber(callejas.get("cbarras")));
    }

    // ── HTML ──────────────────────────────────────────────────────────────────

    private String buildHtml(QuotationResponse quote, Map<String, Object> club,
                             Map<String, Object> header, Map<String, Object> sucursal,
                             List<Card> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"/>
            <style>
              * { box-sizing: border-box; margin: 0; padding: 0; }
              body { font-family: Arial, Helvetica, sans-serif; font-size: 11px; color: #000; padding: 18px 20px; }
              .head { width: 100%; border-bottom: 1px solid #000; padding-bottom: 8px; margin-bottom: 12px; }
              .head td { vertical-align: top; }
              .club { font-size: 12px; font-weight: bold; }
              .small { font-size: 8px; line-height: 1.35; }
              .cards { width: 100%; }
              .card { width: 33%; display: inline-block; vertical-align: top; padding: 6px 6px 10px 6px; page-break-inside: avoid; }
              .card .code { font-size: 12px; font-weight: bold; }
              .card .desc { font-size: 8px; }
              .card .qty  { font-size: 10px; font-weight: bold; }
              .card img   { width: 100%; max-height: 42px; margin-top: 2px; }
              .card .num  { font-size: 9px; letter-spacing: 0.05em; }
              .empty { margin-top: 24px; font-size: 12px; }
            </style></head><body>
            """);

        sb.append("<table class=\"head\"><tr><td width=\"65%\">");
        sb.append("<div class=\"club\">").append(esc(str(club.get("nombre"))))
          .append(" (").append(esc(plainNumber(club.get("ps_tienda_id")))).append(")</div>");
        sb.append("<div class=\"small\">").append(esc(str(club.get("direccion")))).append("</div>");
        sb.append("<div class=\"small\">Tel.: ").append(esc(str(club.get("telefono")))).append("</div>");
        sb.append("<div class=\"small\">Email: bsa")
          .append(esc(plainNumber(club.get("ps_tienda_id")))).append("@pricesmart.com</div>");
        sb.append("</td><td width=\"35%\"><div class=\"small\">");
        sb.append("Orden de Compra: ").append(esc(String.valueOf(quote.getOdc()))).append("<br/>");
        sb.append("Sucursal: ").append(esc(plainNumber(header.get("sucursalid"))))
          .append(" / ").append(esc(str(sucursal.get("nombre")))).append("<br/>");
        sb.append("Fecha: ").append(esc(str(header.get("fecha")))).append("<br/>");
        sb.append("Fecha Entrega: ").append(esc(str(header.get("fecha_entrega"))));
        sb.append("</div></td></tr></table>");

        if (cards.isEmpty()) {
            // Legacy renders an empty gallery and prints a blank sheet, which
            // reads as a broken report rather than as an order with no
            // Callejas lines on it.
            sb.append("<p class=\"empty\">No hay ítems de Callejas en esta cotización.</p>");
        } else {
            sb.append("<div class=\"cards\">");
            for (Card card : cards) {
                sb.append("<div class=\"card\">");
                sb.append("<div class=\"code\">#Item: ").append(esc(card.productId())).append("</div>");
                sb.append("<div class=\"desc\">").append(esc(card.description())).append("</div>");
                sb.append("<div class=\"qty\">Cant.: ").append(esc(card.qty())).append("</div>");
                String png = barcodePng(card.barcode());
                if (png != null) {
                    sb.append("<img src=\"").append(png).append("\"/>");
                }
                sb.append("<div class=\"num\">").append(esc(card.barcode())).append("</div>");
                sb.append("</div>");
            }
            sb.append("</div>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * Code 128 as a data URI. Null when the value cannot be encoded — an empty
     * barcode, or characters Code 128 has no symbol for — so the card still
     * prints with its code, description and quantity and the picker can find
     * the item by hand rather than losing the line entirely.
     */
    private String barcodePng(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BitMatrix matrix = new Code128Writer()
                    .encode(value, BarcodeFormat.CODE_128, BARCODE_WIDTH, BARCODE_HEIGHT);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            log.warn("[CallejasReportService] barcode '{}' could not be encoded: {}", value, e.getMessage());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> first(String sql, Object... params) {
        return Optional.ofNullable(nativeQueries.first(sql, params).orElse(null)).orElse(Map.of());
    }

    /**
     * These columns are `double` in the legacy schema — item codes and barcodes
     * both — so a plain toString gives "607766000130.0". This strips that, and
     * cannot restore a leading zero the column already lost.
     */
    private static String plainNumber(Object value) {
        if (value == null) return "";
        if (value instanceof Number n) return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return value.toString().trim();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
