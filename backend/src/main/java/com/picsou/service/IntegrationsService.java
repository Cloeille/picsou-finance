package com.picsou.service;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Toggles {@code integration.{key}.enabled} flags in {@code app_setting}.
 * The "intent → config → enable" ordering is deliberate: each substep's
 * *success* endpoint is what actually flips the flag to true. The Step 3
 * picker only records user intent in the frontend store.
 *
 * <p>The flag is the admin's choice and nothing else: a missing row reads as
 * enabled (an install configured through {@code .env} never wrote one), and only
 * a stored {@code false} disables. A disabled integration is skipped by
 * {@link MemberSyncService}, the path of every scheduled, startup and assistant
 * sync; manual syncs and connections are not gated.
 */
@Service
public class IntegrationsService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationsService.class);

    /** Sync source in {@link MemberSyncService} → the integration that owns it. */
    private static final Map<String, String> INTEGRATION_BY_SOURCE = Map.ofEntries(
        Map.entry("enable-banking", "enablebanking"),
        Map.entry("enable-banking-retry", "enablebanking"),
        Map.entry("trade-republic", "traderepublic"),
        Map.entry("bourso", "boursobank"),
        Map.entry("bourse-direct", "boursedirect"),
        Map.entry("fortuneo", "fortuneo"),
        Map.entry("lbp", "lbp"),
        Map.entry("finary", "finary"),
        Map.entry("crypto-exchanges", "crypto"),
        Map.entry("wallets", "crypto")
    );

    private final AppSettingRepository settingRepository;

    public IntegrationsService(AppSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    /** The integration that owns a sync source; empty for a source no switch controls. */
    public static Optional<String> integrationOf(String source) {
        return Optional.ofNullable(INTEGRATION_BY_SOURCE.get(source));
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

    /** The stored choice; a missing flag reads as enabled. */
    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        assertKnown(key);
        return settingRepository.findByKey(SetupService.integrationKey(key))
            .map(s -> Boolean.parseBoolean(s.getValue()))
            .orElse(true);
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
