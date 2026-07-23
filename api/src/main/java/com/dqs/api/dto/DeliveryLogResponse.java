package com.dqs.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class DeliveryLogResponse {
    private final Long    logId;
    private final Integer storeId;
    private final Integer statusId;
    private final Integer createdBy;
    private final String  fecha;
    private final String  closedAt;
    private final String  sentAt;
    private final Integer deliveryCount;
    private final List<Map<String, Object>> deliveries;
}
