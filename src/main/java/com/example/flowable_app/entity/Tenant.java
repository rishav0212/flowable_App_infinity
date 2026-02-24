package com.example.flowable_app.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "TBL_TENANTS", schema = "infinity_plus_management")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    // 🟢 Added columnDefinition to enforce the Postgres default at the DB level
    @Column(name = "tenant_id", length = 36, nullable = false, updatable = false,
            columnDefinition = "VARCHAR(36) DEFAULT gen_random_uuid()::varchar")
    private String id;

    @Column(nullable = false, length = 256)
    private String name;


    @Column(name = "slug", nullable = false, length = 100, unique = true)
    private String slug;

    @Column(name = "schema_name", nullable = false, length = 100, unique = true)
    private String schemaName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;


}