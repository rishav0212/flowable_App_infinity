package com.example.flowable_app.features.iam.service;

import com.example.flowable_app.entity.Tenant;
import com.example.flowable_app.repository.TenantRepository;
import com.example.flowable_app.core.security.config.SystemCasbinResourceConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import static org.jooq.impl.DSL.*;


/**
 * Startup utility service responsible for syncing the system-resources.yml definitions
 * into the database for all active tenants. Ensures critical system resources are always available.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemResourceSyncService {

    private final DSLContext dsl;
    private final SystemCasbinResourceConfig resourceConfig;

    // 🟢 Inject your JPA Repository
    private final TenantRepository tenantRepository;

    @Transactional
    public void syncSystemResourcesAcrossAllTenants() {
        log.info("🔄 Starting System Resource Sync from Configuration...");

        if (resourceConfig.getResources() == null || resourceConfig.getResources().isEmpty()) {
            log.warn("⚠️ No system resources found in configuration. Skipping sync.");
            return;
        }

        // 🟢 1. Use the Repository to fetch all tenants cleanly!
        List<Tenant> allTenants = tenantRepository.findAll();

        // 2. Loop through the entities and get their schema names
        for (Tenant tenant : allTenants) {
            String schema = tenant.getSchemaName(); // Use your exact getter name here!

            if (schema != null && !schema.trim().isEmpty()) {
                syncResourcesToSchema(schema);
            }
        }

        log.info("✅ Successfully synced {} system resources across {} tenants.",
                resourceConfig.getResources().size(), allTenants.size());
    }

    public void syncResourcesToSchema(String schema) {
        for (SystemCasbinResourceConfig.ResourceDef res : resourceConfig.getResources()) {
            dsl.insertInto(table(name(schema, "tbl_resources")))
                    .set(field("resource_key"), res.getKey())
                    .set(field("display_name"), res.getDisplayName())
                    .set(field("resource_type"), res.getType())
                    .set(field("description"), res.getDescription()) // 🟢 Insert Description
                    .set(field("is_system"), true)
                    .set(field("created_by"), "SYSTEM")

                    // 🔄 THE UPSERT LOGIC: If the key exists, update these fields!
                    .onConflict(field("resource_key"))
                    .doUpdate()
                    .set(field("display_name"), res.getDisplayName())
                    .set(field("resource_type"), res.getType())      // 🟢 Update type if changed
                    .set(field("description"), res.getDescription()) // 🟢 Update description if changed
                    .set(field("is_system"), true)
                    .execute();

            /*
             * Synchronize the nested actions associated with the resource into the tbl_resource_actions table.
             * By doing this, any newly defined actions in the YAML file automatically become available
             * dynamically across all tenant schemas for permission assignment without writing new code.
             */
            if (res.getActions() != null && !res.getActions().isEmpty()) {
                for (SystemCasbinResourceConfig.ActionDef action : res.getActions()) {
                    dsl.insertInto(table(name(schema, "tbl_resource_actions")))
                            .set(field("resource_key"), res.getKey())
                            .set(field("action_name"), action.getName())
                            .set(field("description"), action.getDescription())
                            .set(field("created_by"), "SYSTEM")

                            // Upsert logic for actions: updates the description if the action already exists
                            .onConflict(field("resource_key"), field("action_name"))
                            .doUpdate()
                            .set(field("description"), action.getDescription())
                            .execute();
                }
            }
        }
    }
}