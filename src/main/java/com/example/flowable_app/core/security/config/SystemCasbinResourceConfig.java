package com.example.flowable_app.core.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "system")
public class SystemCasbinResourceConfig {

    private List<ResourceDef> resources;

    public List<ResourceDef> getResources() { return resources; }
    public void setResources(List<ResourceDef> resources) { this.resources = resources; }

    public static class ResourceDef {
        private String key;
        private String type;
        private String displayName;
        private String description; // 🟢 ADD THIS
        private List<ActionDef> actions;

        // Getters and Setters
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getDescription() { return description; }               // 🟢 ADD THIS
        public void setDescription(String description) { this.description = description; } // 🟢 ADD THIS

        public List<ActionDef> getActions() { return actions; }
        public void setActions(List<ActionDef> actions) { this.actions = actions; }
    }

    public static class ActionDef {
        private String name;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}