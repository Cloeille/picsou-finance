package com.picsou.service;

import com.picsou.dto.InstitutionResponse;
import com.picsou.model.Account;
import com.picsou.port.MerchantLogoPort;
import com.picsou.port.MerchantLogoPort.LogoImage;
import com.picsou.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstitutionLogoServiceTest {

    private static final Long MEMBER = 7L;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 1, 2};
    private static final LogoImage IMAGE = new LogoImage(PNG, "image/png");

    @Mock AccountRepository accountRepository;
    @Mock MerchantLogoPort logoPort;
    private final MutableClock clock = new MutableClock();

    private InstitutionLogoService service() {
        return new InstitutionLogoService(accountRepository, logoPort, clock);
    }

    private void givenAccount(Long id, String domain) {
        when(accountRepository.findByIdAndMemberId(id, MEMBER))
            .thenReturn(Optional.of(Account.builder().id(id).institutionDomain(domain).build()));
    }

    @Test
    void fetchesTheLogoOfTheAccountsInstitutionDomain() {
        givenAccount(1L, "chase.com");
        when(logoPort.fetch("chase.com")).thenReturn(Optional.of(IMAGE));

        assertThat(service().logoFor(1L, MEMBER)).hasValueSatisfying(logo -> {
            assertThat(logo.bytes()).isEqualTo(PNG);
            assertThat(logo.contentType()).isEqualTo("image/png");
        });
    }

    @Test
    void twoAccountsOfOneBank_costOneFetch() {
        givenAccount(1L, "chase.com");
        givenAccount(2L, "chase.com");
        when(logoPort.fetch("chase.com")).thenReturn(Optional.of(IMAGE));
        InstitutionLogoService service = service();

        service.logoFor(1L, MEMBER);
        service.logoFor(2L, MEMBER);

        verify(logoPort, times(1)).fetch("chase.com");
    }

    @Test
    void anotherMembersAccount_isNotFoundAndNothingIsFetched() {
        when(accountRepository.findByIdAndMemberId(1L, MEMBER)).thenReturn(Optional.empty());

        assertThat(service().logoFor(1L, MEMBER)).isEmpty();
        verify(logoPort, never()).fetch(anyString());
    }

    @Test
    void accountWithoutADomain_hasNoLogoAndNothingIsFetched() {
        givenAccount(1L, null);

        assertThat(service().logoFor(1L, MEMBER)).isEmpty();
        verify(logoPort, never()).fetch(anyString());
    }

    @Test
    void failedFetch_isRememberedSoThePageDoesNotRetryOnEveryRender() {
        givenAccount(1L, "chase.com");
        when(logoPort.fetch("chase.com")).thenReturn(Optional.empty());
        InstitutionLogoService service = service();

        service.logoFor(1L, MEMBER);
        service.logoFor(1L, MEMBER);

        verify(logoPort, times(1)).fetch("chase.com");
    }

    private Optional<LogoImage> servedFor(LogoImage upstream) {
        givenAccount(1L, "evil.example");
        when(logoPort.fetch("evil.example")).thenReturn(Optional.of(upstream));
        return service().logoFor(1L, MEMBER);
    }

    @Test
    void anSvgIcon_isRefusedEvenWhenTheUpstreamCallsItAnImage() {
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes();

        assertThat(servedFor(new LogoImage(svg, "image/png"))).isEmpty();
    }

    @Test
    void anHtmlPage_isRefusedEvenWhenLabelledAsAnIcon() {
        byte[] html = "<!doctype html><script>alert(1)</script>".getBytes();

        assertThat(servedFor(new LogoImage(html, "image/x-icon"))).isEmpty();
    }

    @Test
    void anImageLabelledAsHtml_isServedWithTheTypeItsBytesHave() {
        assertThat(servedFor(new LogoImage(PNG, "text/html")))
            .hasValueSatisfying(logo -> assertThat(logo.contentType()).isEqualTo("image/png"));
    }

    @Test
    void anOversizedImage_isRefused() {
        byte[] big = new byte[InstitutionLogoService.MAX_LOGO_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);

        assertThat(servedFor(new LogoImage(big, "image/png"))).isEmpty();
    }

    @Test
    void aRefusedImage_isRememberedAsAMissSoItIsNotRefetchedOnEveryRender() {
        givenAccount(1L, "evil.example");
        when(logoPort.fetch("evil.example")).thenReturn(Optional.of(new LogoImage("<svg/>".getBytes(), "image/svg+xml")));
        InstitutionLogoService service = service();

        service.logoFor(1L, MEMBER);
        service.logoFor(1L, MEMBER);

        verify(logoPort, times(1)).fetch("evil.example");
    }

    @Test
    void aCachedLogo_isFetchedAgainOnceItsDayHasPassed() {
        givenAccount(1L, "chase.com");
        when(logoPort.fetch("chase.com")).thenReturn(Optional.of(IMAGE));
        InstitutionLogoService service = service();

        service.logoFor(1L, MEMBER);
        clock.advance(Duration.ofHours(23));
        service.logoFor(1L, MEMBER);
        verify(logoPort, times(1)).fetch("chase.com");

        clock.advance(Duration.ofHours(2));
        service.logoFor(1L, MEMBER);
        verify(logoPort, times(2)).fetch("chase.com");
    }

    @Test
    void aMiss_isRetriedSoonerThanAHit() {
        givenAccount(1L, "chase.com");
        when(logoPort.fetch("chase.com")).thenReturn(Optional.empty());
        InstitutionLogoService service = service();

        service.logoFor(1L, MEMBER);
        clock.advance(Duration.ofMinutes(61));
        service.logoFor(1L, MEMBER);

        verify(logoPort, times(2)).fetch("chase.com");
    }

    @Test
    void aFullCache_stillServesANewBankWithoutGrowing() {
        InstitutionLogoService service = service();
        for (int i = 0; i < 256; i++) {
            String domain = "bank" + i + ".example";
            when(accountRepository.findByIdAndMemberId((long) i, MEMBER))
                .thenReturn(Optional.of(Account.builder().id((long) i).institutionDomain(domain).build()));
            when(logoPort.fetch(domain)).thenReturn(Optional.of(IMAGE));
            service.logoFor((long) i, MEMBER);
        }
        givenAccount(999L, "one-more.example");
        when(logoPort.fetch("one-more.example")).thenReturn(Optional.of(IMAGE));

        assertThat(service.logoFor(999L, MEMBER)).isPresent();
        service.logoFor(999L, MEMBER);

        verify(logoPort, times(2)).fetch("one-more.example"); // served, not stored
    }

    /** A clock the test moves by hand, so the cache's day-long lifetime needs no sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void institutionResponse_exposesTheNameAndWhetherALogoCanBeFetched_neverTheDomain() {
        Account withLogo = Account.builder().institutionName("Chase Bank").institutionDomain("chase.com").build();
        Account withoutLogo = Account.builder().institutionName("Chase Bank").build();

        assertThat(InstitutionResponse.from(withLogo)).isEqualTo(new InstitutionResponse("Chase Bank", true));
        assertThat(InstitutionResponse.from(withoutLogo)).isEqualTo(new InstitutionResponse("Chase Bank", false));
        assertThat(InstitutionResponse.from(Account.builder().build())).isNull();
    }
}
