package com.example.flowable_app.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "TBL_TOOLJET_WORKSPACES", schema = "infinity_plus_management")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ToolJetWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 🔗 Link to Tenant (One-to-One)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id", nullable = false, unique = true)
    private Tenant tenant;

    @Column(name = "workspace_uuid", nullable = false, unique = true, length = 100)
    private String workspaceUuid;

    @Column(name = "workspace_slug", nullable = false, length = 100)
    private String slug;

    @Column(name = "admin_viewer_email", nullable = false)
    private String viewerEmail;



    @Column(name = "admin_viewer_password", nullable = false)
    private String viewerPassword;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "workspace", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TooljetWorkspaceApp> apps;
}