package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Abstraction over the revolut-auth sidecar (Python + FastAPI + Camoufox/Playwright), which owns
 * a per-member persistent browser profile and harvests the retail API at the Playwright network
 * layer -- below the app's own JS, which cannot be hooked (see docs/features/revolut-sidecar.md).
 *
 * <p>On-demand model: there is no Java-held session/credential blob between calls. Every
 * {@link #sync} call hands the phone+passcode straight to the sidecar, which either reuses a
 * still-live per-member browser profile (no login, no approval) or performs a fresh automated
 * login (the user approves a push notification on their phone) before harvesting. Java may
 * optionally persist the credentials (encrypted) so the scheduler can replay this same call
 * unattended -- see {@code RevolutSyncService}.
 */
public interface RevolutPort {

    record RevolutTxn(
        String externalId,
        LocalDate date,
        String description,
        BigDecimal amount,
        String counterparty
    ) {}

    record RevolutAccountData(
        String externalId,
        String name,
        AccountType type,
        String iban,
        BigDecimal balance,
        String currency,
        /** Non-null for pocket sub-accounts: the external id of their parent wallet. */
        String parentExternalId,
        List<RevolutTxn> txns
    ) {}

    /**
     * Triggers an on-demand sync for this member. The sidecar first tries to reuse a still-live
     * browser profile session (no login, no approval); if it is dead or absent it performs an
     * automated login with {@code phoneNumber}/{@code passcode} (the user approves a push
     * notification on their phone), then harvests wallets + pockets + money-boxes + IBAN +
     * transactions. Returns a flat list; pockets/vaults carry
     * {@link RevolutAccountData#parentExternalId()} pointing at their wallet.
     *
     * <p>This call can block for several minutes while waiting for mobile approval.
     *
     * @throws com.picsou.exception.SyncException with message {@code "SESSION_EXPIRED"} when the
     *         sidecar reports the credentials/session are no longer valid (HTTP 401), or
     *         {@code "APPROVAL_TIMEOUT"} when the mobile push was never approved in time (HTTP 408).
     */
    List<RevolutAccountData> sync(String phoneNumber, String passcode, Long memberId);

    /**
     * Variant for unattended callers. When {@code allowLogin} is false, the sidecar may reuse an
     * existing live browser profile but must not start a fresh login that waits for mobile approval.
     */
    default List<RevolutAccountData> sync(
            String phoneNumber, String passcode, Long memberId, boolean allowLogin) {
        return sync(phoneNumber, passcode, memberId);
    }
}
