package com.picsou.service;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.BourseDirectSessionRepository;
import com.picsou.repository.BoursoSessionRepository;
import com.picsou.repository.FinarySessionRepository;
import com.picsou.repository.FortuneoSessionRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toggles {@code integration.{key}.enabled} flags in {@code app_setting}.
 * The "intent → config → enable" ordering is deliberate: each substep's
 * *success* endpoint is what actually flips the flag to true. The Step 3
 * picker only records user intent in the frontend store; if the user abandons
 * a substep the flag stays false and the post-setup app stays consistent.
 *
 * <p>The stored flag captures wizard intent, but an install configured purely
 * via {@code .env} / docker-compose never runs the wizard, so the flag stays
 * false even though the integration is live. {@link #isEffectivelyEnabled} thus
 * also probes each integration's actual configuration (env credentials, a login
 * session, …) and reports it on when truly present — see that method.
 */
@Service
public class IntegrationsService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationsService.class);

    private final AppSettingRepository settingRepository;
    private final EnableBankingConfigProvider enableBankingConfig;
    private final TradeRepublicSessionRepository tradeRepublicSessions;
    private final FinarySessionRepository finarySessions;
    private final BourseDirectSessionRepository bourseDirectSessions;
    private final BoursoSessionRepository boursoSessions;
    private final FortuneoSessionRepository fortuneoSessions;
    private final UserContext userContext;

    public IntegrationsService(AppSettingRepository settingRepository,
                               EnableBankingConfigProvider enableBankingConfig,
                               TradeRepublicSessionRepository tradeRepublicSessions,
                               FinarySessionRepository finarySessions,
                               BourseDirectSessionRepository bourseDirectSessions,
                               BoursoSessionRepository boursoSessions,
                               FortuneoSessionRepository fortuneoSessions,
                               UserContext userContext) {
        this.settingRepository = settingRepository;
        this.enableBankingConfig = enableBankingConfig;
        this.tradeRepublicSessions = tradeRepublicSessions;
        this.finarySessions = finarySessions;
        this.bourseDirectSessions = bourseDirectSessions;
        this.boursoSessions = boursoSessions;
        this.fortuneoSessions = fortuneoSessions;
        this.userContext = userContext;
    }

    @Transactional
    public void enable(String key) {
        assertKnown(key);
        upsert(SetupService.integrationKey(key), "true");
        log.info("setup.integration.enabled key={}", key);
    }

    @Transactional
    public void disable(String key) {
        assertKnown(key);
        upsert(SetupService.integrationKey(key), "false");
        log.info("setup.integration.disabled key={}", key);
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        assertKnown(key);
        return settingRepository.findByKey(SetupService.integrationKey(key))
            .map(s -> Boolean.parseBoolean(s.getValue()))
            .orElse(false);
    }

    /**
     * Effective on/off state for status surfaces (the admin toggle): the stored
     * wizard flag <em>or</em> a detected live configuration. Per the user
     * directive "don't just trust the boolean — verify what's actually
     * configured", an integration that is genuinely set up (env credentials, a
     * persisted login session) reports on even when its DB flag was never
     * flipped — e.g. a docker-compose install that skipped the wizard.
     *
     * <p>Detection signals differ per integration and are deliberately
     * <strong>cheap</strong> (no network calls, no key parsing):
     * <ul>
     *   <li><strong>enablebanking</strong> — all credentials + a key present
     *       ({@link EnableBankingConfigProvider#isConfiguredLenient()}).</li>
     *   <li><strong>traderepublic</strong> / <strong>finary</strong> — a login
     *       session row exists (these have no env config; auth is runtime).</li>
     *   <li><strong>boursedirect</strong> / <strong>boursobank</strong> /
     *       <strong>fortuneo</strong> — an
     *       active sidecar session row exists.</li>
     *   <li><strong>crypto</strong> (no config to detect) — no cheap signal, so
     *       it falls back to the stored flag.</li>
     * </ul>
     *
     * <p>Because it ORs detection with the flag, this can only <em>reveal</em> a
     * configured-but-untoggled integration; it never hides one the operator
     * explicitly enabled. Note an env-configured integration cannot be turned
     * off via the toggle (the connector reads env directly and ignores the
     * flag) — the switch reflects that reality rather than a stale boolean.
     */
    @Transactional(readOnly = true)
    public boolean isEffectivelyEnabled(String key) {
        assertKnown(key);
        return isEnabled(key) || isDetectedConfigured(key);
    }

    private boolean isDetectedConfigured(String key) {
        return switch (key) {
            case "enablebanking" -> enableBankingConfig.isConfiguredLenient();
            case "traderepublic" -> tradeRepublicSessions.count() > 0;
            case "finary" -> finarySessions.count() > 0;
            case "boursedirect" -> bourseDirectSessions.existsByActiveTrue();
            case "boursobank" -> boursoSessions.existsByActiveTrue();
            case "fortuneo" -> fortuneoSessions.existsByMemberIdAndActiveTrue(
                userContext.currentMemberId()
            );
            default -> false;
        };
    }

    private void assertKnown(String key) {
        if (!SetupService.INTEGRATIONS.contains(key)) {
            throw new IllegalArgumentException("Unknown integration: " + key);
        }
    }

    private void upsert(String key, String value) {
        AppSetting setting = settingRepository.findByKey(key)
            .orElseGet(() -> AppSetting.builder().key(key).build());
        setting.setValue(value);
        settingRepository.save(setting);
    }
}
