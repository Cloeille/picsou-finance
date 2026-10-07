package com.picsou.service;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupServiceAiTest {

    @Mock AppSettingRepository settingRepository;
    @Mock AppUserRepository userRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock PasswordEncoder passwordEncoder;

    SetupService service;

    @BeforeEach
    void setUp() {
        service = new SetupService(settingRepository, userRepository, memberRepository, passwordEncoder);
    }

    @Test
    void writeAiConfig_upsertsProviderModelBaseUrl() {
        when(settingRepository.findByKey(anyString())).thenReturn(Optional.empty());

        service.writeAiConfig("anthropic", "claude-haiku-4-5", "https://api.anthropic.com", null, null);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository, org.mockito.Mockito.times(3)).save(captor.capture());

        List<AppSetting> saved = captor.getAllValues();
        Set<String> keys = saved.stream().map(AppSetting::getKey).collect(Collectors.toSet());
        assertThat(keys).contains("ai.provider", "ai.model", "ai.base-url");

        assertThat(saved).anySatisfy(s -> {
            assertThat(s.getKey()).isEqualTo("ai.provider");
            assertThat(s.getValue()).isEqualTo("anthropic");
        });
        assertThat(saved).anySatisfy(s -> {
            assertThat(s.getKey()).isEqualTo("ai.model");
            assertThat(s.getValue()).isEqualTo("claude-haiku-4-5");
        });
        assertThat(saved).anySatisfy(s -> {
            assertThat(s.getKey()).isEqualTo("ai.base-url");
            assertThat(s.getValue()).isEqualTo("https://api.anthropic.com");
        });
    }

    @Test
    void writeAiConfig_nullKey_doesNotWriteKeyRow() {
        when(settingRepository.findByKey(anyString())).thenReturn(Optional.empty());

        service.writeAiConfig("anthropic", "claude-haiku-4-5", "https://api.anthropic.com", null, null);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository, org.mockito.Mockito.times(3)).save(captor.capture());

        Set<String> keys = captor.getAllValues().stream()
            .map(AppSetting::getKey)
            .collect(Collectors.toSet());
        assertThat(keys).contains("ai.provider", "ai.model", "ai.base-url");
        assertThat(keys).doesNotContain("ai.api-key");
    }

    @Test
    void writeAiConfig_nonNullKey_writesEncryptedKeyRow() {
        when(settingRepository.findByKey(anyString())).thenReturn(Optional.empty());

        service.writeAiConfig("anthropic", "claude-haiku-4-5", "https://api.anthropic.com", "CIPHERTEXT", null);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository, org.mockito.Mockito.times(4)).save(captor.capture());

        assertThat(captor.getAllValues()).anySatisfy(s -> {
            assertThat(s.getKey()).isEqualTo("ai.api-key");
            assertThat(s.getValue()).isEqualTo("CIPHERTEXT");
        });
    }

    @Test
    void writeAiConfig_withMaxConcurrency_upsertsRow() {
        when(settingRepository.findByKey(anyString())).thenReturn(Optional.empty());

        service.writeAiConfig("anthropic", "m", "u", null, 6);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        // provider + model + baseUrl + maxConcurrency = 4 saves (key is null)
        verify(settingRepository, org.mockito.Mockito.times(4)).save(captor.capture());

        assertThat(captor.getAllValues()).anySatisfy(s -> {
            assertThat(s.getKey()).isEqualTo("ai.max-concurrency");
            assertThat(s.getValue()).isEqualTo("6");
        });
    }

    @Test
    void writeAiConfig_nullMaxConcurrency_doesNotWriteRow() {
        when(settingRepository.findByKey(anyString())).thenReturn(Optional.empty());

        service.writeAiConfig("anthropic", "m", "u", null, null);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository, org.mockito.Mockito.times(3)).save(captor.capture());

        Set<String> keys = captor.getAllValues().stream()
            .map(AppSetting::getKey)
            .collect(Collectors.toSet());
        assertThat(keys).doesNotContain("ai.max-concurrency");
    }
}
