package com.example.flowable_app.features.iam;

import com.example.flowable_app.core.response.ApiResponse;
import com.example.flowable_app.core.security.UserContextService;
import com.example.flowable_app.core.security.annotation.RequiresPermission;
import com.example.flowable_app.features.iam.service.CasbinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.*;

@Slf4j
@RestController
@RequestMapping("/api/tenant/permissions")
@RequiredArgsConstructor
@Tag(name = "IAM - Permissions", description = "Endpoints for managing and checking user permissions")
public class PermissionController {

    private final CasbinService casbinService;
    private final UserContextService userContextService;
    private final DSLContext dsl;

    @Operation(summary = "Get my permissions", description = "Dynamically resolves all permissions for the logged-in user based on registered resource actions.")
    @GetMapping("/my-permissions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyPermissions() {
        String userId = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema();

        // 🟢 RESTORED: Dynamic resource action lookup via jOOQ
        Result<Record2<String, String>> resourceActions = dsl.select(
                        field("resource_key", String.class),
                        field("action_name", String.class))
                .from(table(name(schema, "tbl_resource_actions")))
                .fetch();

        Map<String, Boolean> permissions = new LinkedHashMap<>();

        for (Record2<String, String> record : resourceActions) {
            String key = record.value1();
            String action = record.value2();

            // 🟢 RESTORED: Precise Casbin check per registered action
            if (casbinService.canDo(userId, tenantId, schema, key, action)) {
                permissions.put(key + ":" + action, true);
            }
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "userId", userId,
                "tenantId", tenantId,
                "permissions", permissions
        )));
    }

    @Operation(summary = "Get permissions for a role", description = "Returns Casbin policies assigned to a specific role.")
    @RequiresPermission(resource = "module:access_control", action = "read")
    @GetMapping("/roles/{roleId}")
    public ResponseEntity<ApiResponse<List<List<String>>>> getPermissionsForRole(@PathVariable String roleId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();
        return ResponseEntity.ok(ApiResponse.ok(casbinService.getPoliciesForRole(roleId, tenantId, schema)));
    }

    @Operation(summary = "Grant a permission", description = "Grants a role access to a resource/action.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/grant")
    public ResponseEntity<ApiResponse<Void>> grantPermission(@Valid @RequestBody IamDto.Permission.GrantRequest request) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();
        casbinService.grantPermissionToRole(request.getRoleId(), tenantId, schema, request.getResource(), request.getAction());
        return ResponseEntity.ok(ApiResponse.message("Permission granted successfully"));
    }

    @Operation(summary = "Revoke a permission", description = "Revokes a specific Casbin policy.")
    @RequiresPermission(resource = "module:access_control", action = "delete")
    @DeleteMapping("/revoke")
    public ResponseEntity<ApiResponse<Void>> revokePermission(@Valid @RequestBody IamDto.Permission.GrantRequest request) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();
        casbinService.revokePermissionFromRole(request.getRoleId(), tenantId, schema, request.getResource(), request.getAction());
        return ResponseEntity.ok(ApiResponse.message("Permission revoked successfully"));
    }
}