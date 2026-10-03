package com.picsou.mcp.tools;

import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.service.AmexSyncService;
import com.picsou.service.BoursoSyncService;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.SyncService;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.service.UserContext;
import com.picsou.service.WalletSyncService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools that refresh the authenticated member's <em>existing</em> sync connections — exactly the
 * resync methods {@code SchedulerService.dailyBankSync()} runs. They never initiate a new connection,
 * add a wallet/exchange, or re-authenticate: those credential flows are intentionally not exposed as
 * tools, so a key can only ever refresh data the member has already connected.
 *
 * <p>The bean name is set explicitly because Spring AI's {@code McpServerAutoConfiguration} defines a
 * {@code @Bean} factory method named {@code syncTools} (the SYNC server's tool-specification list).
 * Letting this {@code @Component} default to {@code syncTools} collides with it and aborts the context
 * (bean-definition overriding is disabled). The other tool components use unreserved default names.
 */
@Component("picsouSyncTools")
public class SyncTools {

    private final SyncService syncService;
    private final TradeRepublicSyncService tradeRepublicSyncService;
    private final BoursoSyncService boursoSyncService;
    private final AmexSyncService amexSyncService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final WalletSyncService walletSyncService;
    private final UserContext userContext;

    public SyncTools(SyncService syncService,
                     TradeRepublicSyncService tradeRepublicSyncService,
                     BoursoSyncService boursoSyncService,
                     AmexSyncService amexSyncService,
                     CryptoExchangeSyncService cryptoExchangeSyncService,
                     WalletSyncService walletSyncService,
                     UserContext userContext) {
        this.syncService = syncService;
        this.tradeRepublicSyncService = tradeRepublicSyncService;
        this.boursoSyncService = boursoSyncService;
        this.amexSyncService = amexSyncService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.walletSyncService = walletSyncService;
        this.userContext = userContext;
    }

    @Tool(name = "trigger_bank_sync",
        description = "Refresh the authenticated member's existing bank (Enable Banking) connections now. "
            + "Does not connect a new bank or re-authenticate — only refreshes already-linked accounts.")
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerBankSync() {
        syncService.resyncAll(userContext.currentMemberId());
        return "Bank sync triggered for your existing connections.";
    }

    @Tool(name = "trigger_broker_sync",
        description = "Refresh the authenticated member's existing broker connections (Trade Republic and "
            + "BoursoBank) if their session is still active. Does not re-authenticate.")
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerBrokerSync() {
        Long memberId = userContext.currentMemberId();
        tradeRepublicSyncService.resyncIfSessionActive(memberId);
        boursoSyncService.resyncIfSessionActive(memberId);
        amexSyncService.resyncIfSessionActive(memberId);
        return "Broker sync triggered for your active broker sessions.";
    }

    @Tool(name = "trigger_crypto_exchange_sync",
        description = "Refresh the authenticated member's existing crypto-exchange connections now. "
            + "Does not add a new exchange or re-authenticate.")
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerCryptoExchangeSync() {
        cryptoExchangeSyncService.resyncAll(userContext.currentMemberId());
        return "Crypto exchange sync triggered for your existing connections.";
    }

    @Tool(name = "trigger_crypto_wallet_sync",
        description = "Refresh the authenticated member's existing on-chain crypto wallets now. "
            + "Does not add a new wallet.")
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerCryptoWalletSync() {
        WalletSyncService.ResyncSummary summary = walletSyncService.resyncAll(userContext.currentMemberId());
        if (summary.total() == 0) {
            return "No on-chain wallets to sync.";
        }
        String result = "Crypto wallet sync: " + summary.succeeded() + "/" + summary.total() + " succeeded.";
        if (!summary.failed().isEmpty()) {
            result += " Failed: " + summary.failed().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(", ")) + ".";
        }
        return result;
    }
}
