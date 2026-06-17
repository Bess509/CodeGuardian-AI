package com.codeguardian.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.audit.signing")
@Data
public class AuditSigningProperties {

    /**
     * Enable HMAC signatures for newly recorded audit events.
     */
    private boolean enabled = false;

    /**
     * Operator-managed identifier for the active signing key.
     */
    private String keyId = "local";

    /**
     * HMAC secret. Prefer injecting this via an environment variable or secret manager.
     */
    private String secret;

    private String algorithm = "HmacSHA256";

    public boolean isActive() {
        return enabled && secret != null && !secret.isBlank();
    }
}
