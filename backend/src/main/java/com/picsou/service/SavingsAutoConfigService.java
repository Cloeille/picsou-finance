package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.RateBasis;
import com.picsou.model.SavingsProduct;
import com.picsou.repository.SavingsInterestConfigRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Creates the default savings-interest configuration for a bank-imported regulated livret.
 *
 * <p>Bank connectors import the account type (Livret A, LDDS, LEP) but no interest
 * configuration, so the UI lists the account as "to configure" after every sync. For the
 * regulated products the product, the legal rate and the legal ceiling are known, so this
 * service writes them once. It never overwrites a configuration the user already saved: a
 * deleted configuration therefore stays deleted until the next sync recreates it, which is
 * the price of the automatic default.</p>
 *
 * <p>Only the three products that map 1:1 to {@link SavingsProduct} are configured;
 * Livret Jeune, PEL, CEL and generic savings stay manual because their tax/rate rules are
 * not represented by the current model.</p>
 */
@Service
public class SavingsAutoConfigService {

    private final SavingsInterestConfigRepository configRepository;

    public SavingsAutoConfigService(SavingsInterestConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /**
     * Writes the default configuration when the account is a regulated livret and has none.
     *
     * @return true when a row was created
     */
    public boolean configureIfMissing(Account account, AccountType type) {
        Objects.requireNonNull(account, "account");
        Defaults defaults = defaultsFor(type);
        if (defaults == null || account.getId() == null) {
            return false;
        }
        if (configRepository.findByAccountId(account.getId()).isPresent()) {
            return false;
        }
        // Conflict-safe insert, not save-and-catch: a unique-constraint violation would
        // abort the surrounding snapshot transaction on PostgreSQL even when caught.
        // ON CONFLICT DO NOTHING preserves a concurrent user configuration.
        return configRepository.insertIfAbsent(
            account.getId(),
            defaults.product().name(),
            defaults.annualRate(),
            RateBasis.NET.name(),
            defaults.ceiling()
        ) > 0;
    }

    private static Defaults defaultsFor(AccountType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case LIVRET_A -> new Defaults(
                SavingsProduct.LIVRET_A, RegulatedRates.LIVRET_A, RegulatedRates.LIVRET_A_CEILING);
            case LDDS -> new Defaults(
                SavingsProduct.LDDS, RegulatedRates.LDDS, RegulatedRates.LDDS_CEILING);
            case LEP -> new Defaults(
                SavingsProduct.LEP, RegulatedRates.LEP, RegulatedRates.LEP_CEILING);
            default -> null;
        };
    }

    private record Defaults(SavingsProduct product, BigDecimal annualRate, BigDecimal ceiling) {}
}
