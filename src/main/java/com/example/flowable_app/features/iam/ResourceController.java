package com.example.flowable_app.features.iam;

import com.example.flowable_app.core.response.ApiResponse;
import com.example.flowable_app.core.security.UserContextService;
import com.example.flowable_app.core.security.annotation.RequiresPermission;
import com.example.flowable_app.features.iam.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tenant/resources")
@RequiredArgsConstructor
@Tag(name = "IAM - Resources", description = "Endpoints for defining securable resources and custom actions")
public class ResourceController {

    private final UserManagementService userManagementService;
    private final UserContextService userContextService;

    @Operation(summary = "Get all resources",
            description = "Lists all securable resources and their associated actions in the tenant.")
    @RequiresPermission(resource = "module:access_control", action = "view")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getResources() {
        String tenantId = userContextService.getCurrentTenantSchema();
        List<Map<String, Object>> resources = userManagementService.getAllResources(tenantId);
        return ResponseEntity.ok(ApiResponse.ok(resources));
    }

    @Operation(summary = "Create a resource",
            description = "Registers a new business entity or UI module that requires access control.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> createResource(@Valid @RequestBody IamDto.Resource.CreateRequest request) {
        String tenantId = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        userManagementService.registerResource(
                request.getResourceKey(), request.getResourceType(),
                request.getDisplayName(), request.getDescription(), tenantId, currentUserId
        );
        return ResponseEntity.ok(ApiResponse.message("Resource created successfully"));
    }

    @Operation(summary = "Delete a resource",
            description = "Removes a resource and all of its associated custom actions.")
    @RequiresPermission(resource = "module:access_control", action = "delete")
    @DeleteMapping("/{resourceKey}")
    public ResponseEntity<ApiResponse<Void>> deleteResource(@PathVariable String resourceKey) {
        String tenantId = userContextService.getCurrentTenantSchema();
        userManagementService.deleteResource(resourceKey, tenantId);
        return ResponseEntity.ok(ApiResponse.message("Resource deleted successfully"));
    }

    @Operation(summary = "Add custom action",
            description = "Attaches a new dynamic action (e.g., 'approve', 'export') to an existing resource.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/{resourceKey}/actions")
    public ResponseEntity<ApiResponse<Void>> addCustomAction(
            @PathVariable String resourceKey,
            @Valid @RequestBody IamDto.Resource.CustomActionRequest request) {

        String tenantId = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        userManagementService.addCustomActionToResource(
                resourceKey, request.getActionName(), request.getDescription(), tenantId, currentUserId
        );
        return ResponseEntity.ok(ApiResponse.message("Action added successfully"));
    }
}