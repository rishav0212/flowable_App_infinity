package com.example.flowable_app.features.iam;

import com.example.flowable_app.core.response.ApiResponse;
import com.example.flowable_app.core.security.UserContextService;
import com.example.flowable_app.core.security.annotation.RequiresPermission;
import com.example.flowable_app.core.security.config.SystemCasbinResourceConfig;
import com.example.flowable_app.features.iam.service.CasbinService;
import com.example.flowable_app.features.iam.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for Resource management within a tenant.
 * Base URL: /api/tenant/admin/resources
 */
@Slf4j
@RestController
@RequestMapping("/api/tenant/admin/resources")
@RequiredArgsConstructor
@Tag(name = "IAM - Resources", description = "Endpoints for defining securable resources and custom actions")
public class ResourceController {

    private final UserManagementService userManagementService;
    private final UserContextService userContextService;
    private final CasbinService casbinService;

    @Operation(summary = "Get all resources",
            description = "Lists all securable resources and their associated actions in the tenant.")
    @RequiresPermission(resource = "module:access_control", action = "view")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getResources() {
        String schema = userContextService.getCurrentTenantSchema();
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.getAllResources(schema)));
    }

    @Operation(summary = "Create a resource",
            description = "Registers a new business entity or UI module that requires access control.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> createResource(
            @Valid @RequestBody IamDto.Resource.CreateRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        // Convert DTO actions to SystemCasbinResourceConfig.ActionDef
        List<SystemCasbinResourceConfig.ActionDef> actions = new ArrayList<>();
        if (request.getActions() != null) {
            for (IamDto.Resource.ActionRequest actionRequest : request.getActions()) {
                SystemCasbinResourceConfig.ActionDef actionDef = new SystemCasbinResourceConfig.ActionDef();
                actionDef.setName(actionRequest.getName());
                actionDef.setDescription(actionRequest.getDescription());
                actions.add(actionDef);
            }
        }

        userManagementService.registerResource(
                request.getResourceKey(), request.getResourceType(),
                request.getDisplayName(), request.getDescription(),
                schema, currentUserId, actions
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.message("Resource registered successfully"));
    }

    @Operation(summary = "Delete a resource",
            description = "Removes a resource and ALL associated Casbin policies for this resource across all roles.")
    @RequiresPermission(resource = "module:access_control", action = "delete")
    @DeleteMapping("/{resourceKey}")
    public ResponseEntity<ApiResponse<Void>> deleteResource(@PathVariable String resourceKey) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 1. Remove from DB (tbl_resources + tbl_resource_actions)
        userManagementService.deleteResource(resourceKey, schema);

        // 2. Remove all Casbin policies that reference this resource
        // Without this, orphaned policies remain and roles appear to have permissions
        // on a resource that no longer exists
        casbinService.removePoliciesByResource(resourceKey, tenantId, schema);

        return ResponseEntity.ok(ApiResponse.message("Resource deleted successfully"));
    }

    @Operation(summary = "Add custom action",
            description = "Attaches a new dynamic action (e.g., 'approve', 'export') to an existing resource.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/{resourceKey}/actions")
    public ResponseEntity<ApiResponse<Void>> addCustomAction(
            @PathVariable String resourceKey,
            @Valid @RequestBody IamDto.Resource.CustomActionRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        userManagementService.addCustomActionToResource(
                resourceKey, request.getActionName(),
                request.getDescription(), schema, currentUserId
        );

        return ResponseEntity.ok(ApiResponse.message("Action added successfully"));
    }
}