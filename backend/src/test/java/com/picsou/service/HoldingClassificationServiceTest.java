package com.picsou.service;

import com.picsou.dto.HoldingClassificationRequest;
import com.picsou.dto.HoldingClassificationView;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.HoldingClassification;
import com.picsou.model.SecurityProfile;
import com.picsou.model.SecurityProfileStatus;
import com.picsou.model.WealthTier;
import com.picsou.repository.HoldingClassificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldingClassificationServiceTest {

    private static final Long MEMBER = 1L;
    private static final Long ACCOUNT = 7L;

    @Mock HoldingClassificationRepository repository;
    @Mock AccountAccessResolver accessResolver;
    @Mock SecurityProfileService profileService;

    @InjectMocks HoldingClassificationService service;

    private Account account() {
        return Account.builder()
            .id(ACCOUNT).name("CTO").type(AccountType.COMPTE_TITRES).currency("EUR").color("#000")
            .member(FamilyMember.builder().id(MEMBER).build())
            .build();
    }

    @Test
    void theViewKeepsTheOverrideAndTheGuessApart() {
        when(accessResolver.requireReadable(ACCOUNT, MEMBER)).thenReturn(account());
        when(repository.findByMemberIdAndTicker(MEMBER, "AAPL")).thenReturn(Optional.of(
            HoldingClassification.builder().ticker("AAPL").sectorKey("healthcare").build()));
        when(profileService.load(List.of("AAPL"))).thenReturn(Map.of("AAPL",
            SecurityProfile.builder().ticker("AAPL").assetType("STOCK")
                .sectorKey("technology").countryKey("US")
                .refreshedAt(Instant.now()).status(SecurityProfileStatus.OK)
                .slices(new ArrayList<>()).build()));

        HoldingClassificationView view = service.view(ACCOUNT, MEMBER, "aapl");

        // Merging these would make the form unable to say whether you are confirming a guess or
        // reading your own earlier decision.
        assertThat(view.sectorKey()).isEqualTo("healthcare");
        assertThat(view.inferredSectorKey()).isEqualTo("technology");
        assertThat(view.countryKey()).isNull();
        assertThat(view.inferredCountryKey()).isEqualTo("US");
        assertThat(view.profileLooked()).isTrue();
    }

    @Test
    void aTickerNeverLookedUpSaysSoRatherThanLookingUnknowable() {
        when(accessResolver.requireReadable(ACCOUNT, MEMBER)).thenReturn(account());
        when(repository.findByMemberIdAndTicker(MEMBER, "QS0009068550"))
            .thenReturn(Optional.empty());
        when(profileService.load(List.of("QS0009068550"))).thenReturn(Map.of());

        HoldingClassificationView view = service.view(ACCOUNT, MEMBER, "QS0009068550");

        assertThat(view.profileLooked()).isFalse();
        assertThat(view.inferredSectorKey()).isNull();
        assertThat(view.sectorKey()).isNull();
    }

    @Test
    void clearingEveryFieldDropsTheRowRatherThanStoringABlankVerdict() {
        when(accessResolver.requireOwner(ACCOUNT, MEMBER)).thenReturn(account());
        HoldingClassification existing =
            HoldingClassification.builder().ticker("AAPL").sectorKey("technology").build();
        when(repository.findByMemberIdAndTicker(MEMBER, "AAPL")).thenReturn(Optional.of(existing));

        HoldingClassification saved = service.classify(ACCOUNT, MEMBER, "AAPL",
            new HoldingClassificationRequest(null, null, null));

        // A kept-but-empty row would read as "deliberately unclassified" and permanently mask
        // whatever the provider later learns.
        assertThat(saved).isNull();
        verify(repository).delete(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void theOverrideIsStoredUppercasedSoOneCorrectionCoversEveryAccount() {
        when(accessResolver.requireOwner(ACCOUNT, MEMBER)).thenReturn(account());
        when(repository.findByMemberIdAndTicker(MEMBER, "AI.PA")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HoldingClassification saved = service.classify(ACCOUNT, MEMBER, "ai.pa",
            new HoldingClassificationRequest(WealthTier.ALTERNATIVE, "energy", "FR"));

        assertThat(saved.getTicker()).isEqualTo("AI.PA");
        assertThat(saved.getWealthTier()).isEqualTo(WealthTier.ALTERNATIVE);
        assertThat(saved.getSectorKey()).isEqualTo("energy");
        assertThat(saved.getCountryKey()).isEqualTo("FR");
    }
}
