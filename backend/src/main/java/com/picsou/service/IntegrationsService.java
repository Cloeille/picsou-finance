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
 * Toggles {@code integration.{key}.enabled} flags in {@code app_setting}. A missing flag
 * means enabled; a disabled integration is skipped by {@link MemberSyncService}.
 */
@Service
public class IntegrationsService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationsService.class);

    private static final Map<String, String> INTEGRATION_BY_SOURCE = Map.of(
        "enable-banking", "enablebanking",
        "enable-banking-retry", "enablebanking",
        "trade-republic", "traderepublic",
        "bourso", "boursobank",
        "bourse-direct", "boursedirect",
        "fortuneo", "fortuneo",
        "lbp", "lbp",
        "finary", "finary",
        "crypto-exchanges", "crypto",
        "wallets", "crypto"
    );

    private final AppSettingRepository settingRepository;

    public IntegrationsService(AppSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    /** The integration that owns a {@link MemberSyncService} source, if any. */
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
