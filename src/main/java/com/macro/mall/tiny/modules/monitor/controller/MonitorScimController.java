package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macro.mall.tiny.modules.monitor.service.MonitorScimContext;
import com.macro.mall.tiny.modules.monitor.service.MonitorScimCredentialService;
import com.macro.mall.tiny.modules.monitor.service.MonitorScimService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(value = "/scim/v2", produces = "application/scim+json")
@RequiredArgsConstructor
public class MonitorScimController {
    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    private static final String RESOURCE_TYPE_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";
    private static final String SERVICE_PROVIDER_CONFIG_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    private final MonitorScimService scimService;
    private final MonitorScimCredentialService credentialService;
    private final ObjectMapper objectMapper;

    @GetMapping("/ServiceProviderConfig")
    public ObjectNode serviceProviderConfig() {
        ObjectNode config = objectMapper.createObjectNode();
        config.putArray("schemas").add(SERVICE_PROVIDER_CONFIG_SCHEMA);
        config.putObject("patch").put("supported", true);
        config.putObject("bulk").put("supported", false).put("maxOperations", 0).put("maxPayloadSize", 0);
        config.putObject("filter").put("supported", true).put("maxResults", 200);
        config.putObject("changePassword").put("supported", false);
        config.putObject("sort").put("supported", false);
        config.putObject("etag").put("supported", false);
        config.putArray("authenticationSchemes").addObject()
                .put("type", "oauthbearertoken").put("name", "Bearer Token")
                .put("description", "Tenant-scoped SCIM bearer token").put("primary", true);
        return config;
    }

    @GetMapping("/ResourceTypes")
    public ObjectNode resourceTypes() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("schemas").add("urn:ietf:params:scim:api:messages:2.0:ListResponse");
        response.put("totalResults", 2);
        response.put("startIndex", 1);
        response.put("itemsPerPage", 2);
        ArrayNode resources = response.putArray("Resources");
        resources.add(resourceType("User", USER_SCHEMA, "/Users"));
        resources.add(resourceType("Group", GROUP_SCHEMA, "/Groups"));
        return response;
    }

    @GetMapping("/Schemas")
    public ObjectNode schemas() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("schemas").add("urn:ietf:params:scim:api:messages:2.0:ListResponse");
        response.put("totalResults", 2);
        response.put("startIndex", 1);
        response.put("itemsPerPage", 2);
        ArrayNode resources = response.putArray("Resources");
        resources.add(schema(USER_SCHEMA, "User"));
        resources.add(schema(GROUP_SCHEMA, "Group"));
        return response;
    }

    @GetMapping("/Users")
    public JsonNode users(@RequestHeader("Authorization") String authorization,
                          @RequestParam(defaultValue = "1") int startIndex,
                          @RequestParam(defaultValue = "100") int count,
                          @RequestParam(required = false) String filter) {
        return scimService.listUsers(context(authorization), startIndex, count, filter);
    }

    @PostMapping("/Users")
    public ResponseEntity<ObjectNode> createUser(@RequestHeader("Authorization") String authorization,
                                                  @RequestBody JsonNode input) {
        ObjectNode user = scimService.createUser(context(authorization), input);
        return ResponseEntity.created(URI.create(user.path("meta").path("location").asText())).body(user);
    }

    @GetMapping("/Users/{scimId}")
    public ObjectNode user(@RequestHeader("Authorization") String authorization, @PathVariable String scimId) {
        return (ObjectNode) scimService.getUser(context(authorization), scimId);
    }

    @PutMapping("/Users/{scimId}")
    public ObjectNode replaceUser(@RequestHeader("Authorization") String authorization, @PathVariable String scimId,
                                  @RequestBody JsonNode input) {
        return scimService.replaceUser(context(authorization), scimId, input);
    }

    @PatchMapping("/Users/{scimId}")
    public ObjectNode patchUser(@RequestHeader("Authorization") String authorization, @PathVariable String scimId,
                                @RequestBody JsonNode input) {
        return scimService.patchUser(context(authorization), scimId, input);
    }

    @DeleteMapping("/Users/{scimId}")
    public ResponseEntity<Void> deleteUser(@RequestHeader("Authorization") String authorization,
                                           @PathVariable String scimId) {
        scimService.deleteUser(context(authorization), scimId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/Groups")
    public JsonNode groups(@RequestHeader("Authorization") String authorization,
                           @RequestParam(defaultValue = "1") int startIndex,
                           @RequestParam(defaultValue = "100") int count,
                           @RequestParam(required = false) String filter) {
        return scimService.listGroups(context(authorization), startIndex, count, filter);
    }

    @PostMapping("/Groups")
    public ResponseEntity<ObjectNode> createGroup(@RequestHeader("Authorization") String authorization,
                                                   @RequestBody JsonNode input) {
        ObjectNode group = scimService.createGroup(context(authorization), input);
        return ResponseEntity.created(URI.create(group.path("meta").path("location").asText())).body(group);
    }

    @GetMapping("/Groups/{scimId}")
    public ObjectNode group(@RequestHeader("Authorization") String authorization, @PathVariable String scimId) {
        return scimService.getGroup(context(authorization), scimId);
    }

    @PutMapping("/Groups/{scimId}")
    public ObjectNode replaceGroup(@RequestHeader("Authorization") String authorization, @PathVariable String scimId,
                                   @RequestBody JsonNode input) {
        return scimService.replaceGroup(context(authorization), scimId, input);
    }

    @PatchMapping("/Groups/{scimId}")
    public ObjectNode patchGroup(@RequestHeader("Authorization") String authorization, @PathVariable String scimId,
                                 @RequestBody JsonNode input) {
        return scimService.patchGroup(context(authorization), scimId, input);
    }

    @DeleteMapping("/Groups/{scimId}")
    public ResponseEntity<Void> deleteGroup(@RequestHeader("Authorization") String authorization,
                                            @PathVariable String scimId) {
        scimService.deleteGroup(context(authorization), scimId);
        return ResponseEntity.noContent().build();
    }

    MonitorScimContext context(String authorization) {
        return credentialService.authenticate(authorization);
    }

    private ObjectNode resourceType(String name, String schema, String endpoint) {
        ObjectNode resourceType = objectMapper.createObjectNode();
        resourceType.putArray("schemas").add(RESOURCE_TYPE_SCHEMA);
        resourceType.put("id", name);
        resourceType.put("name", name);
        resourceType.put("endpoint", endpoint);
        resourceType.put("description", "SCIM " + name + " resource");
        resourceType.put("schema", schema);
        resourceType.putArray("schemaExtensions");
        return resourceType;
    }

    private ObjectNode schema(String id, String name) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.putArray("schemas").add("urn:ietf:params:scim:schemas:core:2.0:Schema");
        schema.put("id", id);
        schema.put("name", name);
        schema.put("description", "SCIM " + name + " schema");
        ArrayNode attributes = schema.putArray("attributes");
        attribute(attributes, "id", "string", false, false, "readOnly", "always", "server");
        attribute(attributes, "externalId", "string", false, false, "readWrite", "default", "none");
        if ("User".equals(name)) {
            attribute(attributes, "userName", "string", false, true, "readWrite", "default", "server");
            attribute(attributes, "displayName", "string", false, false, "readWrite", "default", "none");
            attribute(attributes, "active", "boolean", false, false, "readWrite", "default", "none");
            attribute(attributes, "emails", "complex", true, false, "readWrite", "default", "none");
        } else {
            attribute(attributes, "displayName", "string", false, true, "readWrite", "default", "none");
            attribute(attributes, "members", "complex", true, false, "readWrite", "default", "none");
        }
        return schema;
    }

    private void attribute(ArrayNode attributes, String name, String type, boolean multiValued, boolean required,
                           String mutability, String returned, String uniqueness) {
        ObjectNode attribute = attributes.addObject();
        attribute.put("name", name);
        attribute.put("type", type);
        attribute.put("multiValued", multiValued);
        attribute.put("required", required);
        attribute.put("mutability", mutability);
        attribute.put("returned", returned);
        attribute.put("uniqueness", uniqueness);
    }
}
