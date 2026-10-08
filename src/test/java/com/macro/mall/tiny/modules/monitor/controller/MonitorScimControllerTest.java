package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.service.MonitorScimCredentialService;
import com.macro.mall.tiny.modules.monitor.service.MonitorScimService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MonitorScimControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MonitorScimController controller = new MonitorScimController(
            mock(MonitorScimService.class), mock(MonitorScimCredentialService.class), objectMapper);
    private final MonitorScimExceptionHandler exceptionHandler = new MonitorScimExceptionHandler(objectMapper);

    @Test
    void advertisesSupportedScimCapabilities() {
        var config = controller.serviceProviderConfig();

        assertEquals("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig",
                config.path("schemas").get(0).asText());
        assertTrue(config.path("patch").path("supported").asBoolean());
        assertTrue(config.path("filter").path("supported").asBoolean());
        assertEquals(200, config.path("filter").path("maxResults").asInt());
    }

    @Test
    void exposesUsersAndGroupsInResourceDiscovery() {
        var resourceTypes = controller.resourceTypes();

        assertEquals(2, resourceTypes.path("totalResults").asInt());
        assertEquals("/Users", resourceTypes.path("Resources").get(0).path("endpoint").asText());
        assertEquals("/Groups", resourceTypes.path("Resources").get(1).path("endpoint").asText());
    }

    @Test
    void returnsScimErrorAndBearerChallengeForUnauthorizedToken() {
        var response = exceptionHandler.handleStatus(
                new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid token"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals(MediaType.valueOf("application/scim+json"), response.getHeaders().getContentType());
        assertEquals("Bearer", response.getHeaders().getFirst("WWW-Authenticate"));
        assertEquals("urn:ietf:params:scim:api:messages:2.0:Error",
                response.getBody().path("schemas").get(0).asText());
        assertEquals("401", response.getBody().path("status").asText());
    }

    @Test
    void returnsScimErrorDetailAndStatusForInvalidRequest() {
        var response = exceptionHandler.handleStatus(
                new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "active must be a boolean"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(MediaType.valueOf("application/scim+json"), response.getHeaders().getContentType());
        assertEquals("urn:ietf:params:scim:api:messages:2.0:Error",
                response.getBody().path("schemas").get(0).asText());
        assertEquals("active must be a boolean", response.getBody().path("detail").asText());
        assertEquals("400", response.getBody().path("status").asText());
    }
}
