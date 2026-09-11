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
 * Not carried over: legacy's El Salvador branch, where a club in 6701-6798
 * whose order has a non-zero {@code orders.odc} is forced to
 * {@code substring(product_id,1,2)} and the radio is ignored entirely. Our
 * quotation carries no odc — the Callejas import that writes it does not exist
 * on our side yet — so there is nothing to branch on. It has to come back with
 * that import, not before.
 */
public enum QuoteItemSort {

    DEPARTMENT (1, QuotationItemResponse::getDepartment),
    CATEGORY   (2, QuotationItemResponse::getCategory),
    PRODUCT_ID (3, QuotationItemResponse::getProductId),
    DESCRIPTION(4, QuotationItemResponse::getDescription);

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

    public List<QuotationItemResponse> sort(List<QuotationItemResponse> items) {
        return items.stream()
            .sorted(Comparator.comparing(key, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }
}
