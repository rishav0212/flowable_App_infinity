package com.example.flowable_app.features.iam;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "Consolidated Data Transfer Objects for Identity and Access Management (IAM)")
public class IamDto {

    // ─── USER PAYLOADS ───────────────────────────────────────────────────────

    public static class User {

        @Data
        @Schema(description = "Payload for creating a new user")
        public static class CreateRequest {

            @Schema(description = "Unique identifier for the user", example = "john_doe_123")
            @NotBlank(message = "User ID is required")
            private String userId;

            @Schema(description = "User's first name", example = "John")
            @NotBlank(message = "First name is required")
            private String firstName;

            @Schema(description = "User's last name", example = "Doe")
            @NotBlank(message = "Last name is required")
            private String lastName;

            @Schema(description = "User's email address", example = "john.doe@example.com")
            @NotBlank(message = "Email is required")
            @Email(message = "Must be a valid email address")
            private String email;

            @Schema(description = "Optional JSON metadata for the user (e.g., department, phone)",
                    example = "{\"department\":\"Engineering\"}")
            private Map<String, Object> metadata;
        }

        @Data
        @Schema(description = "Payload for updating an existing user")
        public static class UpdateRequest {

            @Schema(description = "User's first name", example = "John")
            private String firstName;

            @Schema(description = "User's last name", example = "Doe")
            private String lastName;

            @Schema(description = "User's email address", example = "john.doe@example.com")
            @Email(message = "Must be a valid email address")
            private String email;

            @Schema(description = "Account status flag", example = "true")
            private Boolean isActive;
        }
    }

    // ─── ROLE PAYLOADS ───────────────────────────────────────────────────────

    public static class Role {

        @Data
        @Schema(description = "Payload for creating a new Casbin Role")
        public static class CreateRequest {

            @Schema(description = "Unique internal ID for the role (lowercase, underscores)",
                    example = "tenant_admin")
            @NotBlank(message = "Role ID is required")
            @Pattern(regexp = "^[a-z0-9_]+$",
                    message = "Role ID can only contain lowercase letters, numbers, and underscores")
            private String roleId;

            @Schema(description = "Human-readable role name", example = "Tenant Administrator")
            @NotBlank(message = "Role name is required")
            private String roleName;

            @Schema(description = "Detailed description of the role's purpose",
                    example = "Full access to all tenant resources")
            private String description;
        }

        @Data
        @Schema(description = "Payload for updating an existing Casbin Role")
        public static class UpdateRequest {

            @Schema(description = "Human-readable role name", example = "Tenant Administrator")
            private String roleName;

            @Schema(description = "Detailed description of the role's purpose")
            private String description;
        }
    }

    // ─── RESOURCE PAYLOADS ───────────────────────────────────────────────────

    public static class Resource {

        @Data
        @Schema(description = "Payload for defining a new securable resource")
        public static class CreateRequest {

            @Schema(description = "Unique key for the resource", example = "module:tasks")
            @NotBlank(message = "Resource key is required")
            private String resourceKey;

            @Schema(description = "Categorization type of the resource", example = "page")
            @NotBlank(message = "Resource type is required")
            private String resourceType;

            @Schema(description = "Human-readable display name", example = "Task Management")
            @NotBlank(message = "Display name is required")
            private String displayName;

            @Schema(description = "Description of what this resource protects")
            private String description;

            @Schema(description = "List of actions this resource supports")
            @NotEmpty(message = "At least one action must be defined")
            private List<ActionRequest> actions;
        }

        @Data
        @Schema(description = "A single action definition for a resource")
        public static class ActionRequest {

            @Schema(description = "The action verb", example = "approve")
            @NotBlank(message = "Action name is required")
            private String name;

            @Schema(description = "Description of what this action allows")
            private String description;
        }

        @Data
        @Schema(description = "Payload for defining a custom action on a resource")
        public static class CustomActionRequest {

            @Schema(description = "The action verb", example = "approve")
            @NotBlank(message = "Action name is required")
            private String actionName;

            @Schema(description = "Description of what this action allows")
            private String description;
        }
    }

    // ─── PERMISSION PAYLOADS ─────────────────────────────────────────────────

    public static class Permission {

        @Data
        @Schema(description = "Payload for granting or revoking a permission")
        public static class GrantRequest {

            @Schema(description = "The target Role ID", example = "tenant_admin")
            @NotBlank(message = "Role ID is required")
            private String roleId;

            @Schema(description = "The target Resource Key", example = "module:users")
            @NotBlank(message = "Resource is required")
            private String resource;

            @Schema(description = "The allowed Action", example = "manage")
            @NotBlank(message = "Action is required")
            private String action;
        }
    }
}