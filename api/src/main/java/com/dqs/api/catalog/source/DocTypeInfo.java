package com.dqs.api.catalog.source;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A fiscal document type as the fiscal form needs it.
 *
 * The JSON keys are the legacy ones the frontend reads (StepFiscal.tsx) while
 * the Java names stay English, per the project convention.
 *
 * {@code id} is the legacy `felid`. It is persisted —
 * {@code quotation_fiscal.document_type} holds it, not the code — so the import
 * assigns the felid as the row's explicit id rather than generating one.
 *
 * {@code typeCode} is what the per-type number validation keys off. The
 * description is a display string and free to change; nothing should match on it.
 */
public record DocTypeInfo(
    @JsonProperty("felid")       Integer id,
    @JsonProperty("descripcion") String  description,
    @JsonProperty("typeCode")    String  typeCode,
    @JsonProperty("formato")     String  format
) {}
