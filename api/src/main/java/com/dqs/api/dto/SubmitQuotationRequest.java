// dto/SubmitQuotationRequest.java
package com.dqs.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SubmitQuotationRequest {

    @NotNull(message = "submitted_by es requerido")
    private Integer submittedBy;

    private String notes;
    private Integer paymentMethodId = 0;
}