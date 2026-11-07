package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.port.MerchantLogoPort.LogoImage;
import com.picsou.service.InstitutionLogoService;
import com.picsou.service.UserContext;
import com.picsou.service.budget.BudgetSettingsService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstitutionLogoControllerTest {

    private static final Long MEMBER = 7L;
    private static final LogoImage PNG = new LogoImage(new byte[] {1, 2, 3}, "image/png");

    @Mock InstitutionLogoService logoService;
    @Mock BudgetSettingsService budgetSettingsService;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private final Map<String, Bucket> buckets = new HashMap<>();
    private InstitutionLogoController controller;

    @BeforeEach
    void setUp() {
        controller = new InstitutionLogoController(logoService, budgetSettingsService, userContext, buckets);
        lenient().when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER);
        lenient().when(budgetSettingsService.logoFetchEnabled(MEMBER)).thenReturn(true);
    }

    @Test
    void logosEnabled_servesTheImageWithASandboxingPolicyAndPrivateCaching() {
        when(logoService.logoFor(5L, MEMBER)).thenReturn(Optional.of(PNG));

        ResponseEntity<byte[]> response = controller.logo(5L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(PNG.bytes());
        assertThat(response.getHeaders().getContentType()).hasToString("image/png");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
        assertThat(response.getHeaders().getCacheControl()).contains("private");
    }

    @Test
    void logosDisabled_looksLikeAMissingLogoAndFetchesNothing() {
        when(budgetSettingsService.logoFetchEnabled(MEMBER)).thenReturn(false);

        assertThat(controller.logo(5L, request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(logoService, never()).logoFor(5L, MEMBER);
    }

    @Test
    void noLogoForTheAccount_is404() {
        when(logoService.logoFor(5L, MEMBER)).thenReturn(Optional.empty());

        assertThat(controller.logo(5L, request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theLookupIsScopedToTheSignedInMember() {
        when(logoService.logoFor(5L, MEMBER)).thenReturn(Optional.empty());

        controller.logo(5L, request);

        verify(logoService).logoFor(5L, MEMBER);
    }

    @Test
    void anExhaustedBucket_is429BeforeAnythingIsLookedUp() {
        Bucket spent = RateLimitConfig.createLogoBucket();
        while (spent.tryConsume(1)) { /* drain */ }
        buckets.put("203.0.113.9", spent);

        assertThat(controller.logo(5L, request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(logoService, never()).logoFor(5L, MEMBER);
    }
}
