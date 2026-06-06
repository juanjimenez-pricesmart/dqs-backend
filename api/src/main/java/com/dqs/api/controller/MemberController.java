package com.dqs.api.controller;

import com.dqs.api.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
@Tag(name = "Membresías", description = "Consulta de datos de socios/membresías vía Business API")
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "Buscar membresía", description = "Valida y retorna los datos del socio desde el Business API. El ID debe tener 14 caracteres.")
    @GetMapping("/{membership}")
    public ResponseEntity<Map<String, Object>> getMember(
            @Parameter(description = "Número de membresía (14 caracteres)") @PathVariable String membership) {
        log.info("[MemberController] GET /api/v1/members/{}", membership);
        return ResponseEntity.ok(memberService.getMember(membership));
    }
}
