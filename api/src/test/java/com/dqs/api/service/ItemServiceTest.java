package com.dqs.api.service;

import com.dqs.api.client.BusinessApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * clubsOnhand is the part worth pinning: the first attempt derived it from
 * getItemCode, which answers for one club, and the second read the wrong shape
 * from getQuantity — an object with listQuantityCountry, not a bare array. Both
 * mistakes returned an empty list that looked exactly like an item stocked
 * nowhere.
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    private static final String ITEM = "/api/getItemCode/127738/club/8001";
    private static final String QTY  = "/api/getQuantity/127738/club/8001";

    @Mock private BusinessApiClient businessApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ItemService service() {
        return new ItemService(businessApiClient, objectMapper);
    }

    private String quantityPayload() {
        return """
            { "total_Quantity": 1096.0,
              "listQuantityCountry": [
                { "cost_center": "8001", "qtyOnHand": 258.0 },
                { "cost_center": "8002", "qtyOnHand": 0.0 }
              ] }""";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> onhandOf(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("clubsOnhand");
    }

    // ── the item itself ──────────────────────────────────────────────────────

    @Test
    @DisplayName("an object payload is returned as the item")
    void readsAnObjectPayload() {
        when(businessApiClient.get(ITEM)).thenReturn("{\"item_code\":\"127738\",\"sellPrice\":108.95}");
        when(businessApiClient.get(QTY)).thenReturn(quantityPayload());

        Map<String, Object> item = service().getItemByCode("127738", 8001);

        assertThat(item).containsEntry("item_code", "127738");
    }

    @Test
    @DisplayName("an array payload yields its first element, as the Business API sometimes answers")
    void readsAnArrayPayload() {
        when(businessApiClient.get(ITEM)).thenReturn("[{\"item_code\":\"127738\"},{\"item_code\":\"other\"}]");
        when(businessApiClient.get(QTY)).thenReturn(quantityPayload());

        assertThat(service().getItemByCode("127738", 8001)).containsEntry("item_code", "127738");
    }

    @Test
    @DisplayName("an empty array means the item does not exist")
    void emptyArrayIsNotFound() {
        when(businessApiClient.get(ITEM)).thenReturn("[]");

        assertThatThrownBy(() -> service().getItemByCode("127738", 8001))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Item not found");
    }

    @Test
    @DisplayName("a payload that is not JSON fails loudly")
    void unparseablePayloadThrows() {
        when(businessApiClient.get(ITEM)).thenReturn("<html>error</html>");

        assertThatThrownBy(() -> service().getItemByCode("127738", 8001))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Error consultando item");
    }

    // ── clubsOnhand ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("reads the per-club stock out of listQuantityCountry")
    void readsOnhandFromTheNestedList() {
        when(businessApiClient.get(ITEM)).thenReturn("{\"item_code\":\"127738\"}");
        when(businessApiClient.get(QTY)).thenReturn(quantityPayload());

        List<Map<String, Object>> clubs = onhandOf(service().getItemByCode("127738", 8001));

        assertThat(clubs).hasSize(2);
        assertThat(clubs.get(0)).containsEntry("costCenter", "8001");
        assertThat(clubs.get(0).get("quantityOnHand")).isEqualTo(258.0);
        // zero is kept, not filtered: the frontend paints it as a red badge
        assertThat(clubs.get(1).get("quantityOnHand")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("costCenter is stringified, because the frontend types it as a string")
    void costCenterIsAString() {
        when(businessApiClient.get(ITEM)).thenReturn("{\"item_code\":\"127738\"}");
        when(businessApiClient.get(QTY)).thenReturn(
            "{\"listQuantityCountry\":[{\"cost_center\":8001,\"qtyOnHand\":5}]}");

        assertThat(onhandOf(service().getItemByCode("127738", 8001)).get(0))
            .containsEntry("costCenter", "8001");
    }

    @Test
    @DisplayName("a missing cost centre stays null rather than becoming the text null")
    void missingCostCentreIsNull() {
        when(businessApiClient.get(ITEM)).thenReturn("{\"item_code\":\"127738\"}");
        when(businessApiClient.get(QTY)).thenReturn(
            "{\"listQuantityCountry\":[{\"qtyOnHand\":5}]}");

        assertThat(onhandOf(service().getItemByCode("127738", 8001)).get(0))
            .containsEntry("costCenter", null);
    }

    @Test
    @DisplayName("a bare array is still read, since the sibling endpoint varies its shape")
    void bareArrayIsTolerated() {
        when(businessApiClient.get(ITEM)).thenReturn("{\"item_code\":\"127738\"}");
        when(businessApiClient.get(QTY)).thenReturn("[{\"cost_center\":\"8001\",\"qtyOnHand\":7}]");

        assertThat(onhandOf(service().getItemByCode("127738", 8001))).hasSize(1);
    }

    @Test
    @DisplayName("a payload without listQuantityCountry degrades to no badges")
    void payloadWithoutTheListDegrades() {
        when(businessApiClient.get(ITEM)).thenReturn("{\"item_code\":\"127738\"}");
        when(businessApiClient.get(QTY)).thenReturn("{\"total_Quantity\":10}");

        assertThat(onhandOf(service().getItemByCode("127738", 8001))).isEmpty();
    }

    @Test
    @DisplayName("a null array is read as no badges")
    void nullArrayDegrades() {
        when(businessApiClient.get(ITEM)).thenReturn("{\"item_code\":\"127738\"}");
        when(businessApiClient.get(QTY)).thenReturn("null");

        assertThat(onhandOf(service().getItemByCode("127738", 8001))).isEmpty();
    }

    @Test
    @DisplayName("a stock lookup that fails does not fail the item lookup")
    void onhandFailureDoesNotBreakTheItem() {
        when(businessApiClient.get(ITEM)).thenReturn("{\"item_code\":\"127738\"}");
        when(businessApiClient.get(QTY)).thenThrow(new RuntimeException("upstream down"));

        Map<String, Object> item = service().getItemByCode("127738", 8001);

        // The hot path of every quote must not break over a display detail.
        assertThat(item).containsEntry("item_code", "127738");
        assertThat(onhandOf(item)).isEmpty();
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("search returns an array when the API answers with one")
    void searchReadsAnArray() {
        when(businessApiClient.get(anyString())).thenReturn("[{\"item_code\":\"1\"}]");

        assertThat((Object[]) service().searchItems(6101, "coca")).hasSize(1);
    }

    @Test
    @DisplayName("search returns an object when the API answers with one")
    void searchReadsAnObject() {
        when(businessApiClient.get(anyString())).thenReturn("{\"message\":\"none\"}");

        assertThat(service().searchItems(6101, "coca")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("search encodes the spaces in the description")
    void searchEncodesSpaces() {
        when(businessApiClient.get("/api/getSearch/club/6101/description/coca%20cola"))
            .thenReturn("[]");

        service().searchItems(6101, "coca cola");
    }

    @Test
    @DisplayName("an unparseable search payload fails loudly")
    void searchUnparseableThrows() {
        when(businessApiClient.get(anyString())).thenReturn("<html>");

        assertThatThrownBy(() -> service().searchItems(6101, "coca"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Error buscando items");
    }
}
