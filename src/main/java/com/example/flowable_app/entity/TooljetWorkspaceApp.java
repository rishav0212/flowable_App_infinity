package com.example.flowable_app.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "TBL_TOOLJET_WORKSPACE_APPS",
        schema = "infinity_plus_management",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"workspace_id", "app_key"})
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TooljetWorkspaceApp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 🔗 Link to Workspace
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private ToolJetWorkspace workspace;


    // The actual ToolJet ID
    @Column(name = "tooljet_app_uuid", nullable = false, length = 100)
    private String tooljetAppUuid;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}


