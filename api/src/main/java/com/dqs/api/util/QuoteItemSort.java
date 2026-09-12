package com.dqs.api.util;

import com.dqs.api.dto.QuotationItemResponse;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * How the quotation PDF orders its item lines — legacy's four "Ordenar por"
 * radios, carried over as they are.
 *
 * Legacy sorts in SQL: Model_orders::getOrdersItemDataSort
 * (application/models/Model_orders.php:1079-1154) switches on the radio value
 * and appends one ORDER BY, all ASC. We sort the already-loaded list instead of
 * adding four repository methods — a quotation's lines are a handful of rows
 * and are read in full either way, and department/category live on the product
 * snapshot rather than on quotation_items, so a Sort over them would turn the
 * fetch into an implicit inner join and silently drop any line whose product
 * row is missing.
 *
 * The sort is stable, so lines that tie keep the product_id order the
 * repository already returned them in. Nulls sort first and comparison ignores
 * case, which is what MySQL does for an ASC ORDER BY under the default
 * case-insensitive collation.
 *
 * Legacy's El Salvador branch is carried over too, in {@link #order}: a club in
 * 6701-6798 whose quotation has a Callejas purchase order on it is forced to
 * {@code substring(product_id,1,2)} and the radio is ignored entirely. That
 * branch had to wait for {@code quotations.odc} to exist; it does now.
 */
public enum QuoteItemSort {

    DEPARTMENT (1, QuotationItemResponse::getDepartment),
    CATEGORY   (2, QuotationItemResponse::getCategory),
    PRODUCT_ID (3, QuotationItemResponse::getProductId),
    DESCRIPTION(4, QuotationItemResponse::getDescription);

    /**
     * Legacy's Callejas clubs: {@code 6700 < store_id && store_id < 6799},
     * exclusive at both ends, so 6701-6798. Only 6701-6704 exist today and all
     * 884 orders carrying an odc belong to them, but the bounds are legacy's
     * and a fifth club would fall inside them without anyone touching this.
     */
    private static final int CALLEJAS_MIN = 6701;
    private static final int CALLEJAS_MAX = 6798;

    /**
     * MySQL's {@code ORDER BY substring(product_id,1,2)} — the first two
     * characters, or the whole code when it is shorter, which is what
     * SUBSTRING does rather than failing.
     */
    private static final Comparator<QuotationItemResponse> BY_PRODUCT_PREFIX =
        Comparator.comparing(QuoteItemSort::productPrefix,
                Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));

    private final int code;
    private final Function<QuotationItemResponse, String> key;

    QuoteItemSort(int code, Function<QuotationItemResponse, String> key) {
        this.code = code;
        this.key  = key;
    }

    /**
     * Legacy has two different defaults and they are not the same value.
     *
     * A request that names no sort gets DEPARTMENT: printDiv's signature is
     * {@code printDiv($id, $target = 1, $sortby = 1)} (Orders.php:3860) and the
     * edit screen's hidden frm_ordenado also starts at 1, with the first radio
     * checked. A request that names a sort we do not have — 0, 7, anything
     * outside 1-4 — gets PRODUCT_ID, which is the switch's own default branch.
     */
    public static QuoteItemSort fromCode(Integer code) {
        if (code == null) return DEPARTMENT;
        for (QuoteItemSort sort : values()) {
            if (sort.code == code) return sort;
        }
        return PRODUCT_ID;
    }

    public int code() {
        return code;
    }

    /**
     * The lines in the order this quotation should print them, radio included —
     * or not, when the quotation came from a Callejas purchase order.
     *
     * A Callejas import is grouped by the first two characters of the product
     * code, and the operator's choice is ignored: that grouping is how the
     * picking sheet is walked, so honouring the radio would hand the warehouse
     * a document in an order the shelves are not in.
     *
     * @param odc the Callejas purchase order, or null when the quotation is an
     *            ordinary one. Legacy writes 0 for the same thing and tests
     *            {@code $ver == 0}, which in PHP is true for NULL as well, so
     *            both of its spellings already take the ordinary path there.
     */
    public static List<QuotationItemResponse> order(
            List<QuotationItemResponse> items, Integer sortBy, int storeId, Long odc) {

        if (isCallejasOrder(storeId, odc)) {
            return items.stream().sorted(BY_PRODUCT_PREFIX).toList();
        }
        return fromCode(sortBy).sort(items);
    }

    /** Whether the radio is overridden — a Callejas club with a purchase order on the quotation. */
    public static boolean isCallejasOrder(int storeId, Long odc) {
        return storeId >= CALLEJAS_MIN && storeId <= CALLEJAS_MAX && odc != null && odc != 0L;
    }

    private static String productPrefix(QuotationItemResponse item) {
        String productId = item.getProductId();
        if (productId == null) return null;
        return productId.length() <= 2 ? productId : productId.substring(0, 2);
    }

    public List<QuotationItemResponse> sort(List<QuotationItemResponse> items) {
        return items.stream()
            .sorted(Comparator.comparing(key, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }
}
