package com.example.flowable_app.repository;

import com.example.flowable_app.entity.TooljetWorkspaceApp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TooljetWorkspaceAppRepository extends JpaRepository<TooljetWorkspaceApp, Long> {

    // 🪄 The Magic Query: Joins App -> Workspace -> Tenant to find the correct UUID
    @Query("""
                SELECT a.tooljetAppUuid 
                FROM TooljetWorkspaceApp a 
                WHERE a.workspace.tenant.id = :tenantId
            """)
    Optional<String> findAppIdByTenant(String tenantId, String appKey);


    // 🟢 1. Used by the Sidebar: Fetch all apps for a specific tenant
    @Query("SELECT a FROM TooljetWorkspaceApp a WHERE a.workspace.tenant.id = :tenantId")
    List<TooljetWorkspaceApp> findAllByTenantId(String tenantId);

    // 🔒 2. Used by the Embed Ticket: Security check to ensure Tenant A can't access Tenant B's app
    @Query("""
        SELECT COUNT(a) > 0 
        FROM TooljetWorkspaceApp a 
        WHERE a.tooljetAppUuid = :appId 
          AND a.workspace.tenant.id = :tenantId
    """)
    boolean isAppAllowedForTenant(String tenantId, String appId);
}