package com.picsou.dto;

import com.picsou.model.Account;
import com.picsou.model.RecurringCadence;

import java.math.BigDecimal;

/**
 * The contract facts a provider reports about a wrapper (today only a CORUM Life contract):
 * what was paid in, what is still invested by the provider's own count, and the programmed
 * savings plan. Every figure is EUR and null when not reported.
 */
public record AccountContractResponse(
    BigDecimal paidInEur,
    BigDecimal netInvestedEur,
    BigDecimal scheduledPaymentEur,
    RecurringCadence scheduledPaymentCadence
) {
    /** Null when the account carries none of them, so the UI can test the object alone. */
    public static AccountContractResponse from(Account account) {
        if (account.getProviderPaidInEur() == null && account.getProviderInvestedEur() == null
                && account.getScheduledPaymentEur() == null && account.getScheduledPaymentCadence() == null) {
            return null;
        }
        return new AccountContractResponse(account.getProviderPaidInEur(), account.getProviderInvestedEur(),
            account.getScheduledPaymentEur(), account.getScheduledPaymentCadence());
    }
}
