package com.picsou.service.budget;

import com.picsou.port.MerchantLogoPort;
import com.picsou.port.MerchantLogoPort.LogoImage;
import com.picsou.service.budget.MerchantKnowledgeBase.Brand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The logo cache contract: resolve {@code brandId → logoDomain} through the in-memory KB,
 * fetch at most once per brand per TTL, and cache misses too so an unknown or failing logo
 * is never re-fetched on every avatar render. Pure Mockito — the TTL itself isn't exercised
 * (it's measured in hours), only that a result, present or absent, is memoized.
 */
@ExtendWith(MockitoExtension.class)
class MerchantLogoServiceTest {

    @Mock MerchantKnowledgeBase knowledgeBase;
    @Mock MerchantLogoPort logoPort;

    @InjectMocks MerchantLogoService service;

    private static Brand brand(Long id, String logoDomain) {
        return new Brand(id, "slug", "Display", "courses", "#000000", "D", logoDomain);
    }

    private static LogoImage png() {
        return new LogoImage(new byte[] {1, 2, 3}, "image/png");
    }

    @Test
    void fetchesOnceThenServesFromCache() {
        when(knowledgeBase.findById(1L)).thenReturn(Optional.of(brand(1L, "carrefour.fr")));
        when(logoPort.fetch("carrefour.fr")).thenReturn(Optional.of(png()));

        assertThat(service.getLogo(1L)).isPresent();
        assertThat(service.getLogo(1L)).isPresent(); // second call

        // Fetched from upstream only once — the second call is a cache hit.
        verify(logoPort, times(1)).fetch("carrefour.fr");
    }

    @Test
    void brandWithoutLogoDomainIsAMissAndNeverFetches() {
        when(knowledgeBase.findById(2L)).thenReturn(Optional.of(brand(2L, null)));

        assertThat(service.getLogo(2L)).isEmpty();
        assertThat(service.getLogo(2L)).isEmpty(); // negative cached

        verifyNoInteractions(logoPort);
    }

    @Test
    void upstreamMissIsNegativeCached() {
        when(knowledgeBase.findById(3L)).thenReturn(Optional.of(brand(3L, "unknown.example")));
        when(logoPort.fetch("unknown.example")).thenReturn(Optional.empty());

        assertThat(service.getLogo(3L)).isEmpty();
        assertThat(service.getLogo(3L)).isEmpty();

        // The empty result is remembered — we don't hammer a failing/unknown upstream.
        verify(logoPort, times(1)).fetch("unknown.example");
    }

    @Test
    void unknownBrandIdResolvesToEmpty() {
        when(knowledgeBase.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.getLogo(99L)).isEmpty();

        verifyNoInteractions(logoPort);
    }

    @Test
    void nullBrandIdResolvesToEmptyWithoutTouchingKb() {
        assertThat(service.getLogo(null)).isEmpty();

        verifyNoInteractions(knowledgeBase);
        verifyNoInteractions(logoPort);
    }

    @Test
    void clearCacheForcesARefetch() {
        when(knowledgeBase.findById(1L)).thenReturn(Optional.of(brand(1L, "carrefour.fr")));
        when(logoPort.fetch("carrefour.fr")).thenReturn(Optional.of(png()));

        service.getLogo(1L);
        service.clearCache();
        service.getLogo(1L);

        verify(logoPort, times(2)).fetch("carrefour.fr");
    }
}
