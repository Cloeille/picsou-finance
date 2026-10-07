package com.picsou.config;

import com.picsou.adapter.NoopCategorizer;
import com.picsou.adapter.SpringAiCategorizer;
import com.picsou.dto.AiTestResponse;
import com.picsou.model.AiProvider;
import com.picsou.port.TransactionCategorizerPort;
import com.picsou.service.SetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiConfigProviderTest {

    @Mock
    SetupService setupService;

    @Mock
    AiChatModelFactory factory;

    CryptoEncryption crypto;
    AiConfigProvider provider;

    @BeforeEach
    void setUp() {
        crypto = new CryptoEncryption(Base64.getEncoder().encodeToString(new byte[32]));
        provider = new AiConfigProvider(setupService, crypto, factory);
    }

    // ─── 1. save_encryptsKey_andReloads ──────────────────────────────────────

    @Test
    void save_encryptsKey_andReloads() {
        provider.save("anthropic", "claude-haiku-4-5", "https://api.anthropic.com", "rawkey", null);

        ArgumentCaptor<String> encryptedCaptor = ArgumentCaptor.forClass(String.class);
        verify(setupService).writeAiConfig(
            eq("anthropic"), eq("claude-haiku-4-5"), eq("https://api.anthropic.com"),
            encryptedCaptor.capture(), isNull()
        );
        String captured = encryptedCaptor.getValue();
        assertThat(captured).isNotNull();
        assertThat(crypto.decrypt(captured)).isEqualTo("rawkey");
    }

    // ─── 2. save_blankKey_passesNullToWriteAiConfig ──────────────────────────

    @Test
    void save_blankKey_passesNullToWriteAiConfig() {
        provider.save("anthropic", "m", "u", "   ", null);

        verify(setupService).writeAiConfig("anthropic", "m", "u", null, null);
    }

    // ─── 2b. save_disable_clearsKey ──────────────────────────────────────────

    @Test
    void save_disable_clearsKey() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        provider.save("none", "", "", "", null);

        verify(setupService).writeAiConfig(eq("none"), eq(""), eq(""), keyCaptor.capture(), isNull());
        assertThat(keyCaptor.getValue()).isEqualTo("");
    }

    // ─── 3. current_decryptsStoredKey ────────────────────────────────────────

    @Test
    void current_decryptsStoredKey() {
        when(setupService.readSetting(SetupService.KEY_AI_PROVIDER)).thenReturn(Optional.of("anthropic"));
        when(setupService.readSetting(SetupService.KEY_AI_MODEL)).thenReturn(Optional.of("m"));
        when(setupService.readSetting(SetupService.KEY_AI_BASE_URL)).thenReturn(Optional.of("u"));
        when(setupService.readSetting(SetupService.KEY_AI_API_KEY))
            .thenReturn(Optional.of(crypto.encrypt("secret")));

        Optional<AiProviderConfig> cfg = provider.current();

        assertThat(cfg).isPresent();
        assertThat(cfg.get().provider()).isEqualTo(AiProvider.ANTHROPIC);
        assertThat(cfg.get().apiKey()).isEqualTo("secret");
    }

    // ─── 4. current_noneProvider_empty ───────────────────────────────────────

    @Test
    void current_noneProvider_empty() {
        when(setupService.readSetting(SetupService.KEY_AI_PROVIDER)).thenReturn(Optional.of("none"));

        assertThat(provider.current()).isEmpty();
    }

    // ─── 5. currentCategorizer_unconfigured_returnsNoop ──────────────────────

    @Test
    void currentCategorizer_unconfigured_returnsNoop() {
        when(setupService.readSetting(SetupService.KEY_AI_PROVIDER)).thenReturn(Optional.of("none"));

        TransactionCategorizerPort categorizer = provider.currentCategorizer();

        assertThat(categorizer).isInstanceOf(NoopCategorizer.class);
    }

    // ─── 6. currentCategorizer_configured_buildsSpringAi_andCaches ──────────

    @Test
    void currentCategorizer_configured_buildsSpringAi_andCaches() {
        lenient().when(setupService.readSetting(SetupService.KEY_AI_PROVIDER))
            .thenReturn(Optional.of("anthropic"));
        lenient().when(setupService.readSetting(SetupService.KEY_AI_MODEL))
            .thenReturn(Optional.of("claude-haiku-4-5"));
        lenient().when(setupService.readSetting(SetupService.KEY_AI_BASE_URL))
            .thenReturn(Optional.of("https://api.anthropic.com"));
        lenient().when(setupService.readSetting(SetupService.KEY_AI_API_KEY))
            .thenReturn(Optional.of(crypto.encrypt("k")));
        when(factory.build(any())).thenReturn(Optional.of(mock(ChatModel.class)));

        // First call — cache miss → builds
        TransactionCategorizerPort first = provider.currentCategorizer();
        assertThat(first).isInstanceOf(SpringAiCategorizer.class);

        // Second call — cache hit → no rebuild
        TransactionCategorizerPort second = provider.currentCategorizer();
        assertThat(second).isSameAs(first);
        verify(factory, times(1)).build(any());

        // After reload() — cache evicted → rebuilds
        provider.reload();
        provider.currentCategorizer();
        verify(factory, times(2)).build(any());
    }

    // ─── 7. test_missingKey_returnsNotOk ─────────────────────────────────────

    @Test
    void test_missingKey_returnsNotOk() {
        // Stub current() path so blank key falls back to stored key (which is also absent)
        lenient().when(setupService.readSetting(SetupService.KEY_AI_PROVIDER))
            .thenReturn(Optional.of("openai"));
        lenient().when(setupService.readSetting(SetupService.KEY_AI_MODEL))
            .thenReturn(Optional.empty());
        lenient().when(setupService.readSetting(SetupService.KEY_AI_BASE_URL))
            .thenReturn(Optional.empty());
        lenient().when(setupService.readSetting(SetupService.KEY_AI_API_KEY))
            .thenReturn(Optional.empty());
        when(factory.build(any())).thenReturn(Optional.empty());

        AiTestResponse result = provider.test("openai", "m", "u", "");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).containsIgnoringCase("key");
    }

    // ─── 8. test_factoryThrows_returnsNotOk ──────────────────────────────────

    @Test
    void test_factoryThrows_returnsNotOk() {
        when(factory.build(any())).thenThrow(new RuntimeException("boom"));

        AiTestResponse result = provider.test("openai", "m", "u", "somekey");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("boom");
    }

    // ─── 9. save_evictsCache ─────────────────────────────────────────────────

    @Test
    void save_evictsCache() {
        lenient().when(setupService.readSetting(SetupService.KEY_AI_PROVIDER))
            .thenReturn(Optional.of("anthropic"));
        lenient().when(setupService.readSetting(SetupService.KEY_AI_MODEL))
            .thenReturn(Optional.of("claude-haiku-4-5"));
        lenient().when(setupService.readSetting(SetupService.KEY_AI_BASE_URL))
            .thenReturn(Optional.of("https://api.anthropic.com"));
        lenient().when(setupService.readSetting(SetupService.KEY_AI_API_KEY))
            .thenReturn(Optional.of(crypto.encrypt("k")));
        when(factory.build(any())).thenReturn(Optional.of(mock(ChatModel.class)));

        // Prime the cache — factory must be called once
        provider.currentCategorizer();
        verify(factory, times(1)).build(any());

        // save() evicts the cache
        provider.save("anthropic", "claude-haiku-4-5", "https://api.anthropic.com", "rawkey", null);

        // Next currentCategorizer() call must rebuild — factory called a second time
        provider.currentCategorizer();
        verify(factory, times(2)).build(any());
    }

    // ─── 10. maxConcurrency_defaultsTo4WhenUnset ─────────────────────────────

    @Test
    void maxConcurrency_defaultsTo4WhenUnset() {
        when(setupService.readSetting(SetupService.KEY_AI_MAX_CONCURRENCY)).thenReturn(Optional.empty());

        assertThat(provider.maxConcurrency()).isEqualTo(4);
    }

    // ─── 11. maxConcurrency_clamped ──────────────────────────────────────────

    @Test
    void maxConcurrency_clamped() {
        when(setupService.readSetting(SetupService.KEY_AI_MAX_CONCURRENCY)).thenReturn(Optional.of("0"));
        assertThat(provider.maxConcurrency()).isEqualTo(1);

        when(setupService.readSetting(SetupService.KEY_AI_MAX_CONCURRENCY)).thenReturn(Optional.of("99"));
        assertThat(provider.maxConcurrency()).isEqualTo(16);

        when(setupService.readSetting(SetupService.KEY_AI_MAX_CONCURRENCY)).thenReturn(Optional.of("8"));
        assertThat(provider.maxConcurrency()).isEqualTo(8);
    }

    // ─── 12. save_persistsMaxConcurrency ─────────────────────────────────────

    @Test
    void save_persistsMaxConcurrency() {
        provider.save("anthropic", "m", "u", "rawkey", 6);

        verify(setupService).writeAiConfig(eq("anthropic"), eq("m"), eq("u"), any(), eq(6));
    }

    /*
     * NOTE: The live happy-path test() call (real ChatClient firing a network
     * request and returning "OK") is NOT covered here — it requires a live API
     * endpoint and is exercised manually via the admin Test button.
     */
}
