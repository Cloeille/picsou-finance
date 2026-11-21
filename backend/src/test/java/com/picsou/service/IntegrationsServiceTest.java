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
    void disable_upsertsFalseFlag() {
        when(settingRepository.findByKey("integration.finary.enabled")).thenReturn(Optional.empty());
        integrationsService.disable("finary");
        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getValue()).isEqualTo("false");
    }

    @Test
    void isEnabled_readsFromRepository_defaultsToTrue() {
        when(settingRepository.findByKey("integration.traderepublic.enabled"))
            .thenReturn(Optional.empty());
        assertThat(integrationsService.isEnabled("traderepublic")).isTrue();

        AppSetting off = AppSetting.builder().key("integration.enablebanking.enabled").value("false").build();
        when(settingRepository.findByKey("integration.enablebanking.enabled"))
            .thenReturn(Optional.of(off));
        assertThat(integrationsService.isEnabled("enablebanking")).isFalse();

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
    void integrationKey_followsConvention() {
        assertThat(SetupService.integrationKey("enablebanking"))
            .isEqualTo("integration.enablebanking.enabled");
    }
}
