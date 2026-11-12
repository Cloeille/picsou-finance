package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.model.AppSetting;
import com.picsou.model.LbpSession;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.FinarySessionRepository;
import com.picsou.repository.LbpSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationsServiceTest {

    @Mock AppSettingRepository settingRepository;
    @Mock EnableBankingConfigProvider enableBankingConfig;
    @Mock FinarySessionRepository finarySessions;
    @Mock SidecarSessionRepository sidecarSessions;
    @Mock LbpSessionRepository lbpSessions;
    @Mock UserContext userContext;
    @InjectMocks IntegrationsService integrationsService;

    @Test
    void enable_upsertsTrueFlag_forKnownIntegration() {
        when(settingRepository.findByKey("integration.crypto.enabled")).thenReturn(Optional.empty());

        integrationsService.enable("crypto");

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo("integration.crypto.enabled");
        assertThat(captor.getValue().getValue()).isEqualTo("true");
    }

    @Test
    void disable_upsertsFalseFlag() {
        when(settingRepository.findByKey("integration.finary.enabled")).thenReturn(Optional.empty());
        integrationsService.disable("finary");
        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getValue()).isEqualTo("false");
    }

    @Test
    void isEnabled_readsFromRepository_defaultsToFalse() {
        when(settingRepository.findByKey("integration.traderepublic.enabled"))
            .thenReturn(Optional.empty());
        assertThat(integrationsService.isEnabled("traderepublic")).isFalse();

        AppSetting truthy = AppSetting.builder().key("integration.boursobank.enabled").value("true").build();
        when(settingRepository.findByKey("integration.boursobank.enabled"))
            .thenReturn(Optional.of(truthy));
        assertThat(integrationsService.isEnabled("boursobank")).isTrue();
    }

    @Test
    void enable_rejectsUnknownIntegration() {
        assertThatThrownBy(() -> integrationsService.enable("bitcoin-maxi"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown integration");

        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isEffectivelyEnabled_enableBanking_trueWhenConfiguredViaEnv_evenIfFlagOff() {
        // The reported case: configured purely via .env (flag never flipped by the wizard).
        when(settingRepository.findByKey("integration.enablebanking.enabled")).thenReturn(Optional.empty());
        when(enableBankingConfig.isConfiguredLenient()).thenReturn(true);

        assertThat(integrationsService.isEffectivelyEnabled("enablebanking")).isTrue();
    }

    @Test
    void isEffectivelyEnabled_traderepublic_trueWhenSessionExists_evenIfFlagOff() {
        when(settingRepository.findByKey("integration.traderepublic.enabled")).thenReturn(Optional.empty());
        when(sidecarSessions.countByBank(SidecarBank.TRADE_REPUBLIC)).thenReturn(1L);

        assertThat(integrationsService.isEffectivelyEnabled("traderepublic")).isTrue();
    }

    @Test
    void isEffectivelyEnabled_finary_falseWhenNoSessionAndFlagOff() {
        when(settingRepository.findByKey("integration.finary.enabled")).thenReturn(Optional.empty());
        when(finarySessions.count()).thenReturn(0L);

        assertThat(integrationsService.isEffectivelyEnabled("finary")).isFalse();
    }

    @Test
    void isEffectivelyEnabled_bourseDirect_trueWhenAnActiveSessionExists() {
        when(settingRepository.findByKey("integration.boursedirect.enabled")).thenReturn(Optional.empty());
        when(sidecarSessions.existsByBankAndActiveTrue(SidecarBank.BOURSE_DIRECT)).thenReturn(true);

        assertThat(integrationsService.isEffectivelyEnabled("boursedirect")).isTrue();
    }

    @Test
    void isEffectivelyEnabled_bourseDirect_falseWhenOnlyExpiredSessionsExist() {
        when(settingRepository.findByKey("integration.boursedirect.enabled")).thenReturn(Optional.empty());
        when(sidecarSessions.existsByBankAndActiveTrue(SidecarBank.BOURSE_DIRECT)).thenReturn(false);

        assertThat(integrationsService.isEffectivelyEnabled("boursedirect")).isFalse();
    }

    @Test
    void isEffectivelyEnabled_boursoBank_trueWhenAnActiveSessionExists() {
        when(settingRepository.findByKey("integration.boursobank.enabled")).thenReturn(Optional.empty());
        when(sidecarSessions.existsByBankAndActiveTrue(SidecarBank.BOURSO)).thenReturn(true);

        assertThat(integrationsService.isEffectivelyEnabled("boursobank")).isTrue();
    }

    @Test
    void isEffectivelyEnabled_boursoBank_falseWhenOnlyExpiredSessionsExist() {
        when(settingRepository.findByKey("integration.boursobank.enabled")).thenReturn(Optional.empty());
        when(sidecarSessions.existsByBankAndActiveTrue(SidecarBank.BOURSO)).thenReturn(false);

        assertThat(integrationsService.isEffectivelyEnabled("boursobank")).isFalse();
    }

    @Test
    void isEffectivelyEnabled_fortuneo_trueWhenAnActiveSessionExists() {
        when(settingRepository.findByKey("integration.fortuneo.enabled")).thenReturn(Optional.empty());
        when(userContext.currentMemberId()).thenReturn(7L);
        when(sidecarSessions.existsByBankAndMemberIdAndActiveTrue(SidecarBank.FORTUNEO, 7L)).thenReturn(true);

        assertThat(integrationsService.isEffectivelyEnabled("fortuneo")).isTrue();
    }

    @Test
    void isEffectivelyEnabled_fortuneo_falseWhenOnlyExpiredSessionsExist() {
        when(settingRepository.findByKey("integration.fortuneo.enabled")).thenReturn(Optional.empty());
        when(userContext.currentMemberId()).thenReturn(7L);
        when(sidecarSessions.existsByBankAndMemberIdAndActiveTrue(SidecarBank.FORTUNEO, 7L)).thenReturn(false);

        assertThat(integrationsService.isEffectivelyEnabled("fortuneo")).isFalse();
    }

    @Test
    void isEffectivelyEnabled_lbp_trueWhenAnActiveSessionExists() {
        when(settingRepository.findByKey("integration.lbp.enabled")).thenReturn(Optional.empty());
        when(userContext.currentMemberId()).thenReturn(7L);
        when(lbpSessions.findByMemberId(7L))
            .thenReturn(Optional.of(LbpSession.builder().active(true).build()));

        assertThat(integrationsService.isEffectivelyEnabled("lbp")).isTrue();
    }

    @Test
    void isEffectivelyEnabled_lbp_falseWhenOnlyAnInactiveSessionExists() {
        when(settingRepository.findByKey("integration.lbp.enabled")).thenReturn(Optional.empty());
        when(userContext.currentMemberId()).thenReturn(7L);
        when(lbpSessions.findByMemberId(7L))
            .thenReturn(Optional.of(LbpSession.builder().active(false).build()));

        assertThat(integrationsService.isEffectivelyEnabled("lbp")).isFalse();
    }

    @Test
    void isEffectivelyEnabled_crypto_fallsBackToStoredFlag_whenNoDetectionSignal() {
        // crypto has no cheap config signal, so the stored flag is authoritative.
        AppSetting truthy = AppSetting.builder().key("integration.crypto.enabled").value("true").build();
        when(settingRepository.findByKey("integration.crypto.enabled")).thenReturn(Optional.of(truthy));

        assertThat(integrationsService.isEffectivelyEnabled("crypto")).isTrue();
    }

    @Test
    void integrationKey_followsConvention() {
        assertThat(SetupService.integrationKey("enablebanking"))
            .isEqualTo("integration.enablebanking.enabled");
    }
}
