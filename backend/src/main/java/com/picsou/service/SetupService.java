package com.picsou.service;

import com.picsou.dto.SetupStatusResponse;
import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.SetupState;
import com.picsou.model.UserRole;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    public static final String KEY_SETUP_STATE = "setup.state";
    public static final String KEY_INTEGRATION_PREFIX = "integration.";
    public static final String KEY_INTEGRATION_SUFFIX_ENABLED = ".enabled";
    public static final String KEY_CORS_ALLOWED_ORIGINS = "cors.allowed-origins";
    public static final String KEY_SECURE_COOKIES = "app.secure-cookies";
    public static final String KEY_ENABLEBANKING_APP_ID = "enablebanking.application-id";
    public static final String KEY_ENABLEBANKING_KEY_ID = "enablebanking.key-id";
    public static final String KEY_ENABLEBANKING_REDIRECT_URI = "enablebanking.redirect-uri";
    public static final String KEY_BOURSO_AUTH_URL = "bourso-auth.url";
    public static final String KEY_AI_PROVIDER = "ai.provider";
    public static final String KEY_AI_MODEL = "ai.model";
    public static final String KEY_AI_BASE_URL = "ai.base-url";
    public static final String KEY_AI_API_KEY = "ai.api-key";
    public static final String KEY_AI_MAX_CONCURRENCY = "ai.max-concurrency";
    public static final List<String> INTEGRATIONS = List.of(
        "enablebanking", "boursobank", "boursedirect", "fortuneo", "traderepublic", "finary", "crypto"
    );

    private final AppSettingRepository settingRepository;
    private final AppUserRepository userRepository;
    private final FamilyMemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public SetupService(AppSettingRepository settingRepository,
                        AppUserRepository userRepository,
                        FamilyMemberRepository memberRepository,
                        PasswordEncoder passwordEncoder) {
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public SetupStatusResponse getStatus() {
        SetupState state = currentState();
        Map<String, Boolean> integrations = new LinkedHashMap<>();
        for (String name : INTEGRATIONS) {
            integrations.put(name, readBool(integrationKey(name), false));
        }
        return new SetupStatusResponse(state, state != SetupState.COMPLETE, integrations);
    }

    public SetupState currentState() {
        return settingRepository.findByKey(KEY_SETUP_STATE)
            .map(s -> SetupState.valueOf(s.getValue()))
            .orElse(SetupState.PENDING_ADMIN);
    }

    public boolean isComplete() {
        return currentState() == SetupState.COMPLETE;
    }

    /**
     * Seeds the bootstrap admin. Called either by the legacy env-based
     * {@code DataSeeder} path or by the setup wizard controller.
     *
     * SERIALIZABLE isolation + a CAS on setup.state prevents two racing
     * callers (e.g. two browser tabs) from each creating an admin. The wizard
     * seeds exactly one account: once any user exists, further calls are
     * rejected regardless of {@code setup.state} — additional members/admins
     * are created from the authenticated Family admin UI, never from here.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AppUser seedAdmin(String username, String bcryptHash, String displayName, String avatarColor) {
        if (userRepository.existsByUsername(username)) {
            log.info("Admin '{}' already exists, skipping seed", username);
            return userRepository.findByUsernameWithMember(username).orElseThrow();
        }

        SetupState state = currentState();
        if (state == SetupState.COMPLETE) {
            throw new IllegalStateException("Setup is already complete; use the admin UI to manage users.");
        }

        if (userRepository.count() > 0) {
            throw new IllegalStateException(
                "An account already exists; additional members are created from the Family admin UI.");
        }

        int claimed = settingRepository.compareAndSet(
            KEY_SETUP_STATE,
            SetupState.PENDING_ADMIN.name(),
            SetupState.IN_PROGRESS.name()
        );
        if (claimed == 0 && state == SetupState.PENDING_ADMIN) {
            // Two concurrent seeding attempts raced on the PENDING_ADMIN → IN_PROGRESS CAS
            // (e.g. double-submitted wizard tabs). Log it so the resulting 5xx is
            // distinguishable from a genuine server error in the logs.
            log.warn("Setup: concurrent admin-seeding attempt lost the setup-state CAS (state={})", state);
            throw new IllegalStateException("Another setup session is already in progress.");
        }

        if (!bcryptHash.startsWith("$2")) {
            throw new IllegalArgumentException(
                "Password hash must be a valid bcrypt hash starting with $2a$, $2b$, or $2y$."
            );
        }

        FamilyMember member = FamilyMember.builder()
            .displayName(displayName != null ? displayName : username)
            .avatarColor(avatarColor != null ? avatarColor : "#6366f1")
            .managed(false)
            .build();
        memberRepository.save(member);

        AppUser user = AppUser.builder()
            .username(username)
            .passwordHash(bcryptHash)
            .member(member)
            .role(UserRole.ADMIN)
            .activated(true)
            .acknowledgedWarning(true)
            .build();
        userRepository.save(user);

        log.info("setup.admin.created username={} role=ADMIN", username);
        return user;
    }

    /**
     * Hashes a plaintext password with the shared BCrypt encoder. Exposed so
     * the wizard controller can turn a plaintext field into the hash format
     * accepted by {@link #seedAdmin}.
     */
    public String hashPassword(String plaintext) {
        return passwordEncoder.encode(plaintext);
    }

    /**
     * Writes the CORS allowed-origins list and secure-cookies toggle to
     * {@code app_setting}. Called by the wizard's security step; consumed
     * on every request by {@code DynamicCorsConfigurationSource} (origins)
     * and {@code AuthController} (cookies).
     *
     * Origins are stored as a comma-separated string to match the env-var
     * format ({@code ALLOWED_ORIGINS=a,b,c}), so the two configuration
     * paths parse identically.
     */
    @Transactional
    public void writeSecurity(List<String> allowedOrigins, boolean secureCookies) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed origin is required.");
        }
        if (allowedOrigins.stream().anyMatch(o -> o == null || o.contains("*"))) {
            throw new IllegalArgumentException("Wildcard origins are not allowed with credentialed CORS; list explicit origins.");
        }
        String joined = String.join(",", allowedOrigins);
        upsert(KEY_CORS_ALLOWED_ORIGINS, joined);
        upsert(KEY_SECURE_COOKIES, Boolean.toString(secureCookies));
        log.info("setup.security.updated origins={} secure={}", allowedOrigins.size(), secureCookies);
    }

    /**
     * Overwrites the persisted CORS origins with the value currently provided
     * by the {@code ALLOWED_ORIGINS} env var (mapped onto
     * {@code app.cors.allowed-origins}). This is the escape hatch for
     * operators who switched their public URL after the setup wizard ran:
     * normally DB > env, but here the operator explicitly asks env to win
     * once. The wizard's other CORS rules (no wildcards, non-empty) still
     * apply.
     */
    @Transactional
    public void reloadCorsFromEnv(String envOrigins) {
        if (envOrigins == null || envOrigins.isBlank()) {
            throw new IllegalArgumentException("ALLOWED_ORIGINS env var is empty — set it before reloading.");
        }
        List<String> parsed = java.util.Arrays.stream(envOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("ALLOWED_ORIGINS env var contains no usable origins.");
        }
        if (parsed.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalArgumentException("Wildcard origins are not allowed with credentialed CORS.");
        }
        String joined = String.join(",", parsed);
        upsert(KEY_CORS_ALLOWED_ORIGINS, joined);
        log.info("setup.security.cors-reloaded-from-env origins={}", parsed.size());
    }

    /**
     * Persists Enable Banking connection credentials (app-id, redirect URI).
     * The private key is handled separately by
     * {@code EnableBankingKeyPairService} since it lives on the filesystem,
     * not in {@code app_setting}.
     *
     * <p>Per Enable Banking's spec the JWT {@code kid} <em>is</em> the
     * application id, so we no longer collect a separate Key ID and instead
     * store {@code key-id = applicationId} in lock-step. Keeping the row in sync
     * matters because {@link EnableBankingConfigProvider#resolve} reads the DB
     * first — a stale {@code key-id} row would otherwise win over a
     * later-changed application id.
     */
    @Transactional
    public void writeEnableBankingConfig(String applicationId, String redirectUri) {
        upsert(KEY_ENABLEBANKING_APP_ID, applicationId);
        upsert(KEY_ENABLEBANKING_KEY_ID, applicationId);
        upsert(KEY_ENABLEBANKING_REDIRECT_URI, redirectUri);
        log.info("setup.integration.enablebanking.configured");
    }

    @Transactional
    public void writeAiConfig(String provider, String model, String baseUrl, String encryptedKeyOrNull, Integer maxConcurrency) {
        upsert(KEY_AI_PROVIDER, provider == null ? "none" : provider);
        upsert(KEY_AI_MODEL, model == null ? "" : model);
        upsert(KEY_AI_BASE_URL, baseUrl == null ? "" : baseUrl);
        if (encryptedKeyOrNull != null) {
            upsert(KEY_AI_API_KEY, encryptedKeyOrNull);
        }
        if (maxConcurrency != null) {
            upsert(KEY_AI_MAX_CONCURRENCY, Integer.toString(maxConcurrency));
        }
        log.info("setup.ai.configured provider={}", provider);
    }

    @Transactional
    public void markComplete() {
        upsert(KEY_SETUP_STATE, SetupState.COMPLETE.name());
        log.info("setup.completed");
    }

    /**
     * Convenience read used by {@code EnableBankingConfigProvider} and by
     * the wizard UI when re-rendering a partially filled form.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<String> readSetting(String key) {
        return settingRepository.findByKey(key).map(AppSetting::getValue);
    }

    void upsert(String key, String value) {
        AppSetting setting = settingRepository.findByKey(key)
            .orElseGet(() -> AppSetting.builder().key(key).build());
        setting.setValue(value);
        settingRepository.save(setting);
    }

    private boolean readBool(String key, boolean defaultValue) {
        return settingRepository.findByKey(key)
            .map(s -> Boolean.parseBoolean(s.getValue()))
            .orElse(defaultValue);
    }

    public static String integrationKey(String integration) {
        return KEY_INTEGRATION_PREFIX + integration + KEY_INTEGRATION_SUFFIX_ENABLED;
    }
}
