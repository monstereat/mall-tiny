package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorSamlExchangeRequest;
import com.macro.mall.tiny.modules.monitor.service.MonitorSamlLoginCodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

@RestController
@RequestMapping("/monitor/sso")
@RequiredArgsConstructor
public class MonitorSamlController {
    private final MonitorSamlLoginCodeService loginCodeService;

    @PostMapping("/exchange")
    public CommonResult<Map<String, String>> exchange(
            @Valid @RequestBody MonitorSamlExchangeRequest request,
            HttpServletResponse response) {
        String token = loginCodeService.exchange(request.getCode());
        if (token == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SAML login code is invalid or expired");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return CommonResult.success(Map.of("token", token, "tokenHead", "Bearer "));
    }
}
