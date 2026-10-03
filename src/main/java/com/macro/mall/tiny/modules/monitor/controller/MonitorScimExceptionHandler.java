package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = MonitorScimController.class)
@RequiredArgsConstructor
public class MonitorScimExceptionHandler {
    private final ObjectMapper objectMapper;

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ObjectNode> handleStatus(ResponseStatusException exception) {
        ObjectNode body = error(exception.getStatusCode().value(), exception.getReason());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/scim+json"));
        if (exception.getStatusCode().value() == 401) headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return ResponseEntity.status(exception.getStatusCode()).headers(headers).body(body);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ObjectNode> handleMissingHeader(MissingRequestHeaderException exception) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/scim+json"));
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return ResponseEntity.status(401).headers(headers).body(error(401, "SCIM bearer token required"));
    }

    private ObjectNode error(int status, String detail) {
        ObjectNode error = objectMapper.createObjectNode();
        error.putArray("schemas").add("urn:ietf:params:scim:api:messages:2.0:Error");
        error.put("detail", detail == null ? "SCIM request failed" : detail);
        error.put("status", Integer.toString(status));
        return error;
    }
}
