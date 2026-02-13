package com.example.flowable_app;

import com.example.flowable_app.config.FlowableEndpointSecurityInterceptor;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.TimeZone;

@SpringBootApplication
@EnableFeignClients
@EnableScheduling
// 👇 1. Define the API Information
@OpenAPIDefinition(
        info = @Info(title = "Flowable App Backend",
                version = "1.0",
                description = "Combined API for Custom App & Flowable Engine"),
        security = @SecurityRequirement(name = "bearerAuth") // Applies security globally
)
// 👇 2. Define the "Authorize" Button (JWT)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class FlowableAppApplication implements WebMvcConfigurer {


    @Autowired
    private FlowableEndpointSecurityInterceptor securityInterceptor;

    public static void main(String[] args) {
        SpringApplication.run(FlowableAppApplication.class, args);
    }

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    /**
     * Register the IDOR security interceptor to run on all Flowable API calls.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityInterceptor)
                .addPathPatterns("/process-api/**") // Target standard Flowable APIs
                .addPathPatterns("/dmn-api/**")     // Target DMN APIs if used
                .addPathPatterns("/idm-api/**");    // Target IDM APIs if used
    }
}


