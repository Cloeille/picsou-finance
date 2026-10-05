package com.picsou.service;

import com.picsou.model.SecurityProfile;
import com.picsou.repository.SecurityProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityIdentityServiceTest {

    private static final String ISIN = "LU1681043599";

    @Mock SecurityProfileRepository repository;

    @InjectMocks SecurityIdentityService service;

    @Test
    void aTickerThatIsItselfAnIsinNeedsNoLookup() {
        // Employee-savings FCPEs are the common case: no exchange quotes them, so the sync
        // stores the ISIN as the ticker. Going to the database for it would be pointless.
        assertThat(service.isinOf("lu1681043599")).contains(ISIN);
        verify(repository, never()).findByTicker(any());
    }

    @Test
    void otherwiseTheStoredIsinIsUsed() {
        when(repository.findByTicker("CW8.PA")).thenReturn(Optional.of(
            SecurityProfile.builder().ticker("CW8.PA").assetType("ETF").isin(ISIN).build()));

        assertThat(service.isinOf("cw8.pa")).contains(ISIN);
    }

    @Test
    void anUnknownOrMalformedIsinIsNotReturned() {
        when(repository.findByTicker("AAPL")).thenReturn(Optional.of(
            SecurityProfile.builder().ticker("AAPL").assetType("STOCK").isin("not-an-isin").build()));

        // A junk value stored by some earlier path must not reach a provider as if it were real.
        assertThat(service.isinOf("AAPL")).isEmpty();
        assertThat(service.isinOf(null)).isEmpty();
    }

    @Test
    void recordingSeedsAProfileThatHasNeverBeenResolved() {
        when(repository.findByTicker("CW8.PA")).thenReturn(Optional.empty());

        service.record(Map.of("cw8.pa", ISIN));

        ArgumentCaptor<SecurityProfile> saved = ArgumentCaptor.forClass(SecurityProfile.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTicker()).isEqualTo("CW8.PA");
        assertThat(saved.getValue().getIsin()).isEqualTo(ISIN);
        // Null is what refreshStale reads as "due". A sentinel timestamp here would look like a
        // profile resolved moments ago and lock the row out of the next pass.
        assertThat(saved.getValue().getRefreshedAt()).isNull();
    }

    @Test
    void recordingDoesNotOverwriteAnIsinAlreadyKnown() {
        SecurityProfile existing = SecurityProfile.builder()
            .ticker("CW8.PA").assetType("ETF").isin(ISIN)
            .refreshedAt(Instant.now()).build();
        when(repository.findByTicker("CW8.PA")).thenReturn(Optional.of(existing));

        service.record(Map.of("CW8.PA", "IE00B4L5Y983"));

        verify(repository, never()).save(any());
        assertThat(existing.getIsin()).isEqualTo(ISIN);
    }

    @Test
    void aFailureNeverPropagatesToTheSync() {
        when(repository.findByTicker(any())).thenThrow(new RuntimeException("db is down"));

        // This runs on the sync write path. Reference data the next sync will supply again is
        // not worth failing an import over.
        assertThatCode(() -> service.record(Map.of("CW8.PA", ISIN))).doesNotThrowAnyException();
    }

    @Test
    void aNonIsinValueIsIgnoredRatherThanStored() {
        service.record(Map.of("AAPL", "US-garbage"));

        verify(repository, never()).save(any());
    }
}
