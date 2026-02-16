package com.example.flowable_app.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "TBL_TENANTS", schema = "infinity_plus_management")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tenant {

    @Id
    @Column(name = "tenant_id", length = 256, nullable = false)
    private String id; // e.g. "acme-corp"

    @Column(nullable = false, length = 256)
    private String name;


    @Column(name = "slug", nullable = false, length = 100, unique = true)
    private String slug;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;


}