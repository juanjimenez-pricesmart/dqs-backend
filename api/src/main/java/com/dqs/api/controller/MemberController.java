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
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
@Tag(name = "Membresías", description = "Consulta de datos de socios/membresías vía Business API")
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "Buscar socios por nombre", description = "Retorna hasta 50 coincidencias para que el operador seleccione una membresía.")
    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchMembers(
            @Parameter(description = "Nombre o parte del nombre") @RequestParam String name) {
        log.info("[MemberController] GET /api/v1/members/search");
        return ResponseEntity.ok(memberService.searchMembers(name));
    }

    @Operation(summary = "Buscar membresía",
               description = "Valida y retorna los datos del socio desde el Business API, con las correcciones " +
                             "locales de contacto aplicadas encima. El ID debe tener 14 caracteres.")
    @GetMapping("/{membership}")
    public ResponseEntity<Map<String, Object>> getMember(
            @Parameter(description = "Número de membresía (14 caracteres)") @PathVariable String membership) {
        log.info("[MemberController] GET /api/v1/members/{}", membership);
        return ResponseEntity.ok(memberService.getMember(membership));
    }

    @Operation(summary = "Corregir datos de contacto del socio",
               description = "Guarda dirección, teléfono, correo y razón social corregidos por el personal del club, " +
                             "y retorna la membresía ya con la corrección aplicada. Equivale a " +
                             "orders/savedatamembership del legacy, que escribe ps_socios; acá se guarda en " +
                             "member_contact_overrides, que es tabla propia. Rechaza el guardado si los cuatro " +
                             "campos vienen vacíos.")
    @PutMapping("/{membership}/contact")
    public ResponseEntity<Map<String, Object>> saveContactOverride(
            @Parameter(description = "Número de membresía (14 caracteres)") @PathVariable String membership,
            @RequestBody Map<String, Object> body) {
        log.info("[MemberController] PUT /api/v1/members/{}/contact", membership);
        return ResponseEntity.ok(memberService.saveContactOverride(membership, body));
    }
}
