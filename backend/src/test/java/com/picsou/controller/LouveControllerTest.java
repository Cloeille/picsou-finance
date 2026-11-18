package com.picsou.controller;

import com.picsou.config.DynamicCorsConfigurationSource;
import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.service.LouveSyncService;
import com.picsou.service.SetupService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The redirect URI Picsou registers with Louve is one of the instance's configured origins. */
class LouveControllerTest {

    private final LouveSyncService service = mock(LouveSyncService.class);

    /** {@code saved} is the Administration > Security list (null: never saved), {@code env} ALLOWED_ORIGINS. */
    private LouveController controller(String saved, String env) {
        AppSettingRepository settings = mock(AppSettingRepository.class);
        when(settings.findByKey(SetupService.KEY_CORS_ALLOWED_ORIGINS)).thenReturn(Optional.ofNullable(saved)
            .map(value -> AppSetting.builder().key(SetupService.KEY_CORS_ALLOWED_ORIGINS).value(value).build()));
        UserContext user = mock(UserContext.class);
        when(user.currentMemberId()).thenReturn(7L);
        return new LouveController(service, user, new DynamicCorsConfigurationSource(settings, env),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    private static MockHttpServletRequest start(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/louve/auth/start");
        if (origin != null) request.addHeader("Origin", origin);
        return request;
    }

    @Test
    void theRedirectUriIsTheConfiguredOriginTheBrowserIsOn() {
        LouveController controller = controller("https://picsou.example, https://money.example:8443", "");

        controller.start(start("https://money.example:8443"));

        verify(service).startConnection(7L, "https://money.example:8443/sync/louve/callback");
        assertThat(controller.allowedOrigin("https://picsou.example")).isEqualTo("https://picsou.example");
    }

    @Test
    void theEnvironmentOriginsApplyUntilTheListIsSaved() {
        assertThat(controller(null, "https://picsou.example").allowedOrigin("https://picsou.example"))
            .isEqualTo("https://picsou.example");
        assertThat(controller("https://money.example", "https://picsou.example").allowedOrigin("https://picsou.example"))
            .isNull();
    }

    @Test
    void aDefaultPortOnEitherSideStillMatches() {
        assertThat(controller("https://picsou.example:443", "").allowedOrigin("https://picsou.example"))
            .isEqualTo("https://picsou.example");
        assertThat(controller("http://localhost:80", "").allowedOrigin("http://localhost"))
            .isEqualTo("http://localhost");
        assertThat(controller("https://picsou.example/", "").allowedOrigin("https://PICSOU.example:443"))
            .isEqualTo("https://picsou.example");
        assertThat(controller("https://picsou.example:8443", "").allowedOrigin("https://picsou.example"))
            .isNull();
    }

    @Test
    void anOriginOutsideTheListIsRefusedWithACode() {
        LouveController controller = controller("https://picsou.example", "");

        for (String origin : new String[] {"https://evil.example", null, " "}) {
            ResponseEntity<?> response = controller.start(start(origin));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(((ProblemDetail) response.getBody()).getProperties())
                .containsEntry("code", LouveController.ORIGIN_NOT_ALLOWED);
        }
        verifyNoInteractions(service);
    }

    @Test
    void withNoOriginConfiguredEveryOriginIsRefused() {
        assertThat(controller(null, "").allowedOrigin("https://picsou.example")).isNull();
        assertThat(controller(null, "*").allowedOrigin("https://picsou.example")).isNull();
    }

    @Test
    void plainHttpIsOnlyAcceptedOnLoopback() {
        assertThat(LouveController.redirectUri("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173/sync/louve/callback");
        assertThat(LouveController.redirectUri("http://localhost:5173")).isEqualTo("http://localhost:5173/sync/louve/callback");
        assertThat(LouveController.redirectUri("http://192.0.2.10:8080")).isNull();
        assertThat(LouveController.redirectUri("https://Picsou.Example")).isEqualTo("https://picsou.example/sync/louve/callback");
        assertThat(LouveController.redirectUri("https://picsou.example/path")).isNull();
        assertThat(LouveController.redirectUri("https://user@picsou.example")).isNull();
    }

    @Test
    void aConfiguredPlainHttpOriginIsRefusedWithItsOwnCode() {
        ResponseEntity<?> response = controller("http://192.0.2.10:8080", "").start(start("http://192.0.2.10:8080"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((ProblemDetail) response.getBody()).getProperties())
            .containsEntry("code", LouveController.ORIGIN_NOT_SECURE);
        verify(service, org.mockito.Mockito.never()).startConnection(anyLong(), anyString());
    }
}
