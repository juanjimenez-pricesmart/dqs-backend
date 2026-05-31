// dto/CloseQuotationRequest.java
package com.dqs.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CloseQuotationRequest {

    @NotNull(message = "submittedBy es requerido")
    @JsonProperty("submittedBy")
    private Integer submittedBy;

    @JsonProperty("quoteTypeId")
    private Integer quoteTypeId = 0;

    @JsonProperty("paymentNumber")
    private String paymentNumber = "";

    @JsonProperty("paymentMethodId")
    private String paymentMethodId = "0";
}