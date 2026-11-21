package com.picsou.service;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationsServiceTest {

    @Mock AppSettingRepository settingRepository;
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
    void disable_overwritesAnExistingFlagWithFalse() {
        AppSetting stored = AppSetting.builder().key("integration.finary.enabled").value("true").build();
        when(settingRepository.findByKey("integration.finary.enabled")).thenReturn(Optional.of(stored));

        integrationsService.disable("finary");

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(stored);
        assertThat(captor.getValue().getValue()).isEqualTo("false");
    }

    @Test
    void isEnabled_missingFlag_readsAsEnabled() {
        // An install configured through .env never wrote the flag.
        when(settingRepository.findByKey("integration.traderepublic.enabled")).thenReturn(Optional.empty());

        assertThat(integrationsService.isEnabled("traderepublic")).isTrue();
    }

    @Test
    void isEnabled_storedFalse_readsBackAsDisabled() {
        // #666: the switch read back on whatever was stored, once the integration was set up.
        AppSetting off = AppSetting.builder().key("integration.enablebanking.enabled").value("false").build();
        when(settingRepository.findByKey("integration.enablebanking.enabled")).thenReturn(Optional.of(off));

        assertThat(integrationsService.isEnabled("enablebanking")).isFalse();
    }

    @Test
    void isEnabled_storedTrue_readsAsEnabled() {
        AppSetting on = AppSetting.builder().key("integration.boursobank.enabled").value("true").build();
        when(settingRepository.findByKey("integration.boursobank.enabled")).thenReturn(Optional.of(on));

        assertThat(integrationsService.isEnabled("boursobank")).isTrue();
    }

    @Test
    void enable_rejectsUnknownIntegration() {
        assertThatThrownBy(() -> integrationsService.enable("bitcoin-maxi"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown integration");

        verify(settingRepository, never()).save(any());
    }

    @Test
    void integrationOf_mapsEachSyncSourceToItsIntegration() {
        assertThat(IntegrationsService.integrationOf("enable-banking")).contains("enablebanking");
        assertThat(IntegrationsService.integrationOf("enable-banking-retry")).contains("enablebanking");
        assertThat(IntegrationsService.integrationOf("trade-republic")).contains("traderepublic");
        assertThat(IntegrationsService.integrationOf("bourso")).contains("boursobank");
        assertThat(IntegrationsService.integrationOf("bourse-direct")).contains("boursedirect");
        assertThat(IntegrationsService.integrationOf("fortuneo")).contains("fortuneo");
        assertThat(IntegrationsService.integrationOf("lbp")).contains("lbp");
        assertThat(IntegrationsService.integrationOf("finary")).contains("finary");
        assertThat(IntegrationsService.integrationOf("crypto-exchanges")).contains("crypto");
        assertThat(IntegrationsService.integrationOf("wallets")).contains("crypto");
    }

    @Test
    void integrationOf_sourceWithoutASwitch_isEmpty() {
        assertThat(IntegrationsService.integrationOf("revolut")).isEmpty();
        assertThat(IntegrationsService.integrationOf("degiro")).isEmpty();
    }

    @Test
    void integrationKey_followsConvention() {
        assertThat(SetupService.integrationKey("enablebanking"))
            .isEqualTo("integration.enablebanking.enabled");
    }
}
