package com.example.flowable_app.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

@Configuration
public class ToolJetOAuth2ServerConfig {

    /**
     * 1. Authorization Server Filter Chain
     * Intercepts requests to /oauth2/token and handles the OAuth2 handshake natively.
     * We use HIGHEST_PRECEDENCE so it runs before your main SecurityConfig.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();

        http
                // 🟢 CRITICAL FIX: Ensure this filter chain ONLY intercepts OAuth2 protocol endpoints.
                // Without this, the HIGHEST_PRECEDENCE order would hijack all your normal API traffic.
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())

                // Apply the Authorization Server configuration safely to the HttpSecurity object
                .with(authorizationServerConfigurer, (authorizationServer) ->
                        authorizationServer.oidc(Customizer.withDefaults())
                )

                // Require authentication for these specific OAuth2 endpoints
                .authorizeHttpRequests((authorize) ->
                        authorize.anyRequest().authenticated()
                );

        return http.build();
    }

    /**
     * 2. Client Registration
     * Registers ToolJet as a machine-to-machine client in memory.
     * We grant it the "internal:read" scope and enforce a strict 5-minute token expiry.
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        RegisteredClient tooljetClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("tooljet-internal-client")
                .clientSecret("{noop}tooljet-super-secret-key-123") // Must have {noop} here!
                // 🟢 FIX 1: Allow BOTH Basic Auth and Body (POST) Auth. ToolJet often messes this up.
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                // 🟢 FIX 2: Ensure it accepts Client Credentials
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("internal:read")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(tooljetClient);
    }
    /**
     * 3. RSA Key Generation for signing the JWTs.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(UUID.randomUUID().toString()).build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    /**
     * 4. Local JWT Decoder
     * Allows our Resource Server (in the next file) to validate the tokens locally in-memory
     * without making an external HTTP request.
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }
}