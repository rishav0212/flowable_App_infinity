package com.example.flowable_app.repository;

import com.example.flowable_app.entity.TooljetWorkspaceApp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}