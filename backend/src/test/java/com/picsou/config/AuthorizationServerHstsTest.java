package com.picsou.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OAuth2 authorization-server chain ({@code @Order(1)}) shares the API chain's headers and its
 * HSTS gate. It used to send {@code Strict-Transport-Security} on any HTTPS request whatever
 * {@code HSTS_ENABLED} said, which is the lockout the gate exists to prevent on a locally-issued
 * certificate. Boots the real context (as {@code AuthorizationServerMetadataTest} does) because the
 * chain wiring is the point.
 */
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationServerHstsTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    abstract static class Fixture {
        @DynamicPropertySource
        static void secrets(DynamicPropertyRegistry registry) {
            registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
            registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        }

        @Autowired MockMvc mvc;
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.hsts-enabled=false")
    class WhenDisabled extends Fixture {

        @Test
        void jwksOverHttps_omitsHstsButKeepsTheOtherHeaders() throws Exception {
            mvc.perform(get("/oauth2/jwks").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.hsts-enabled=true")
    class WhenEnabled extends Fixture {

        @Test
        void jwksOverHttps_sendsHsts() throws Exception {
            mvc.perform(get("/oauth2/jwks").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
        }

        @Test
        void jwksOverPlainHttp_omitsHsts() throws Exception {
            mvc.perform(get("/oauth2/jwks").secure(false))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
    }
}
