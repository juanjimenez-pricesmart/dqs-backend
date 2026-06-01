package com.dqs.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SendToOmsRequest {

    @NotNull(message = "submittedBy es requerido")
    @JsonProperty("submittedBy")
    private Integer submittedBy;

    @JsonProperty("ventanas")
    private String ventanas = "";
}
