package com.example.flowable_app.repository;

import com.example.flowable_app.entity.ToolJetWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ToolJetWorkspaceRepository extends JpaRepository<ToolJetWorkspace, Long> {
    // Finds the configuration for a specific tenant
    Optional<ToolJetWorkspace> findByTenantId(String tenantId);
}