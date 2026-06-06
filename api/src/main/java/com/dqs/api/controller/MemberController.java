package com.dqs.api.controller;

import com.dqs.api.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/{membership}")
    public ResponseEntity<Map<String, Object>> getMember(@PathVariable String membership) {
        log.info("[MemberController] GET /api/v1/members/{}", membership);
        return ResponseEntity.ok(memberService.getMember(membership));
    }
}
