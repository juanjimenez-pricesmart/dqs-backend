package com.dqs.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class QuotationDocumentResponse {

    private Long id;
    private Long quotationId;
    private String documentType;
    private String fileName;
    private String storageUrl;
    private Long uploadedByUserId;
    private LocalDateTime createdAt;
}
