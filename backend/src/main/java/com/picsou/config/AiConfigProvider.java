package com.picsou.config;

import com.picsou.adapter.NoopCategorizer;
import com.picsou.adapter.SpringAiCategorizer;
import com.picsou.dto.AiTestResponse;
import com.picsou.model.AiProvider;
import com.picsou.port.TransactionCategorizerPort;
import com.picsou.service.SetupService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Runtime resolver for AI categorization provider configuration.
 *
 * <p><strong>DB-only</strong> — unlike {@code EnableBankingConfigProvider}, there is no env-var
 * fallback. AI config is always written through the admin panel and stored in {@code app_setting};
 * the application starts with AI disabled (Noop) until the admin configures it.
 *
 * <p><strong>Encrypted key at rest</strong> — the raw API key is encrypted with
 * {@link CryptoEncryption} before being written to the database; {@link #current()} decrypts it
 * transparently on read.
 *
 * <p><strong>Cached categorizer</strong> — {@link #currentCategorizer()} uses a volatile
 * double-checked-lock cache so the {@link ChatModel} is built once and reused across requests.
 * Call {@link #reload()} after any config change (done automatically by {@link #save}) to evict
 * the cache and force a rebuild on the next call.
 *
 * <p><strong>Test() uses a raw {@link ChatClient}</strong> — NOT the cached
 * {@link SpringAiCategorizer}. The categorizer's {@code categorize()} contract says "never throw",
 * so it swallows all exceptions including auth/network errors and returns empty. For the admin Test
 * button we need those errors to surface as readable messages; the raw {@code ChatClient.create()}
 * call lets them propagate.
 */
@Component
public class AiConfigProvider {

    private final SetupService setupService;
    private final CryptoEncryption crypto;
    private final AiChatModelFactory factory;

    private volatile TransactionCategorizerPort cached;

    public AiConfigProvider(SetupService setupService,
                            CryptoEncryption crypto,
                            AiChatModelFactory factory) {
        this.setupService = setupService;
        this.crypto = crypto;
        this.factory = factory;
    }

    // ─── Config accessors ────────────────────────────────────────────────────

    /**
     * Reads the four AI settings from the DB, decrypts the API key, and returns the assembled
     * config — or empty when no provider is configured (provider absent or "none").
     */
    public Optional<AiProviderConfig> current() {
        Optional<AiProvider> p = AiProvider.fromKey(
            setupService.readSetting(SetupService.KEY_AI_PROVIDER).orElse(null));
        if (p.isEmpty()) return Optional.empty();
        String model = setupService.readSetting(SetupService.KEY_AI_MODEL).orElse("");
        String baseUrl = setupService.readSetting(SetupService.KEY_AI_BASE_URL).orElse("");
        String key = setupService.readSetting(SetupService.KEY_AI_API_KEY)
            .filter(s -> !s.isBlank())
            .map(crypto::decrypt)
            .orElse(null);
        return Optional.of(new AiProviderConfig(p.get(), key, model, baseUrl));
    }

    // ─── Cached categorizer ──────────────────────────────────────────────────

    /**
     * Returns the active {@link TransactionCategorizerPort}, building and caching it on first
     * call. Returns a {@link NoopCategorizer} when no provider is configured or the factory
     * declines to build (e.g. key required but absent).
     */
    public TransactionCategorizerPort currentCategorizer() {
        TransactionCategorizerPort c = cached;
        if (c == null) {
            synchronized (this) {
                c = cached;
                if (c == null) {
                    c = buildCategorizer();
                    cached = c;
                }
            }
        }
        return c;
    }

    private TransactionCategorizerPort buildCategorizer() {
        return current().flatMap(factory::build)
            .<TransactionCategorizerPort>map(SpringAiCategorizer::new)
            .orElseGet(NoopCategorizer::new);
    }

    /** Evicts the categorizer cache so the next call to {@link #currentCategorizer()} rebuilds. */
    public void reload() {
        cached = null;
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    /**
     * Persists AI config. A blank {@code rawApiKeyOrBlank} means "keep the existing key" (passes
     * {@code null} to {@link SetupService#writeAiConfig} which skips the key update). A non-blank
     * value is encrypted before storage. Calls {@link #reload()} afterwards.
     */
    public void save(String provider, String model, String baseUrl, String rawApiKeyOrBlank, Integer maxConcurrency) {
        boolean disabling = AiProvider.fromKey(provider).isEmpty();
        String encrypted = disabling
            ? ""  // clear the stored key when AI is turned off
            : (rawApiKeyOrBlank == null || rawApiKeyOrBlank.isBlank() ? null : crypto.encrypt(rawApiKeyOrBlank));
        setupService.writeAiConfig(provider, model, baseUrl, encrypted, maxConcurrency);
        reload();
    }

    // ─── Test ────────────────────────────────────────────────────────────────

    /**
     * Builds an ephemeral {@link ChatModel} from the submitted values (without persisting) and
     * fires a minimal prompt. A blank key reuses the stored (decrypted) key so the admin can test
     * without re-entering it.
     *
     * <p>Uses a raw {@link ChatClient} call — NOT the {@link SpringAiCategorizer} — so that
     * connection and authentication errors propagate and are returned as readable messages.
     */
    public AiTestResponse test(String provider, String model, String baseUrl, String rawApiKeyOrBlank) {
        Optional<AiProvider> p = AiProvider.fromKey(provider);
        if (p.isEmpty()) return new AiTestResponse(false, "No provider selected.");
        try {
            String key = (rawApiKeyOrBlank == null || rawApiKeyOrBlank.isBlank())
                ? current().map(AiProviderConfig::apiKey).orElse(null)
                : rawApiKeyOrBlank;
            Optional<ChatModel> built = factory.build(new AiProviderConfig(p.get(), key, model, baseUrl));
            if (built.isEmpty()) return new AiTestResponse(false, "An API key is required for " + p.get() + ".");
            String reply = ChatClient.create(built.get())
                .prompt()
                .user("Reply with the single word: OK")
                .call()
                .content();
            return new AiTestResponse(true,
                "Connected. Model replied: " + (reply == null ? "" : reply.strip()));
        } catch (Exception e) {
            return new AiTestResponse(false,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ─── Concurrency setting ─────────────────────────────────────────────────

    /**
     * Returns the configured max concurrency for AI categorization jobs (default 4, clamped 1..16).
     */
    public int maxConcurrency() {
        int v = setupService.readSetting(SetupService.KEY_AI_MAX_CONCURRENCY).map(s -> {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 4;
            }
        }).orElse(4);
        return Math.max(1, Math.min(16, v));
    }

    // ─── Status accessors (for admin GET) ────────────────────────────────────

    /** The stored provider key, or {@code "none"} when absent/blank. */
    public String storedProvider() {
        return setupService.readSetting(SetupService.KEY_AI_PROVIDER)
            .filter(s -> !s.isBlank())
            .orElse("none");
    }

    /** The stored model string, or empty string when absent. */
    public String storedModel() {
        return setupService.readSetting(SetupService.KEY_AI_MODEL).orElse("");
    }

    /** The stored base URL, or empty string when absent. */
    public String storedBaseUrl() {
        return setupService.readSetting(SetupService.KEY_AI_BASE_URL).orElse("");
    }

    /** {@code true} when an (encrypted) API key is stored in the DB. */
    public boolean apiKeyPresent() {
        return setupService.readSetting(SetupService.KEY_AI_API_KEY)
            .filter(s -> !s.isBlank())
            .isPresent();
    }
}
