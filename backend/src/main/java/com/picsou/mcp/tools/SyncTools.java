package com.picsou.mcp.tools;

import com.picsou.config.RateLimitConfig;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.service.MemberSyncService;
import com.picsou.service.SyncStatusService;
import com.picsou.service.UserContext;
import com.picsou.service.sync.SourceSyncResult;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tools that refresh the authenticated member's existing connections.
 * Every trigger goes through {@link MemberSyncService}, the same owner as the 08:00 scheduler.
 * They never open a new connection or re-authenticate.
 *
 * <p>The bean name is set explicitly because Spring AI's {@code McpServerAutoConfiguration} defines a
 * {@code @Bean} factory method named {@code syncTools}. A default {@code syncTools} name collides
 * with it.
 */
@Component("picsouSyncTools")
public class SyncTools {

    private static final String SHARED_COOLDOWN_DESCRIPTION = " Cooldown is shared by all MCP trigger tools for this member: "
        + "one sync every 15 minutes, and four per day.";

    static final Set<String> BANK_SOURCES = Set.of("enable-banking", "enable-banking-retry");
    static final Set<String> BROKER_SOURCES = Set.of(
        "trade-republic", "bourso", "bourse-direct", "amundi", "fortuneo", "amex", "ibkr", "degiro");
    static final Set<String> EXCHANGE_SOURCES = Set.of("crypto-exchanges");
    static final Set<String> WALLET_SOURCES = Set.of("wallets");

    private final MemberSyncService memberSyncService;
    private final SyncStatusService syncStatusService;
    private final UserContext userContext;
    private final Map<Long, Bucket> memberSyncBuckets;

    public SyncTools(MemberSyncService memberSyncService,
                     SyncStatusService syncStatusService,
                     UserContext userContext,
                     @Qualifier("mcpMemberSyncBuckets") Map<Long, Bucket> memberSyncBuckets) {
        this.memberSyncService = memberSyncService;
        this.syncStatusService = syncStatusService;
        this.userContext = userContext;
        this.memberSyncBuckets = memberSyncBuckets;
    }

    @Tool(name = "trigger_full_sync",
        description = "Refresh every existing connector for the authenticated member, in the same order "
            + "as the 08:00 scheduler. DEGIRO is included because this call is user-initiated. "
            + "Does not connect a new source or re-authenticate. Returns one status line per source; "
            + "a failure or a source that needs reauthentication is named, not hidden. "
            + SHARED_COOLDOWN_DESCRIPTION)
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerFullSync() {
        return trigger(null);
    }

    @Tool(name = "trigger_bank_sync",
        description = "Refresh the authenticated member's existing Enable Banking connections, including "
            + "the retry of connections that failed last time. Does not include Revolut — use "
            + "trigger_full_sync for every source. Does not connect a new bank. Returns one status "
            + "line per source; a failure is named, not hidden." + SHARED_COOLDOWN_DESCRIPTION)
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerBankSync() {
        return trigger(BANK_SOURCES);
    }

    @Tool(name = "trigger_broker_sync",
        description = "Refresh the authenticated member's existing broker connections: Trade Republic, "
            + "BoursoBank, Bourse Direct, Amundi, Fortuneo, American Express, IBKR, and DEGIRO when the session is "
            + "still active. Does not re-authenticate. Returns one status line per source." + SHARED_COOLDOWN_DESCRIPTION)
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerBrokerSync() {
        return trigger(BROKER_SOURCES);
    }

    @Tool(name = "trigger_crypto_exchange_sync",
        description = "Refresh the authenticated member's existing crypto-exchange connections. "
            + "Does not add an exchange. Returns one status line per source." + SHARED_COOLDOWN_DESCRIPTION)
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerCryptoExchangeSync() {
        return trigger(EXCHANGE_SOURCES);
    }

    @Tool(name = "trigger_crypto_wallet_sync",
        description = "Refresh the authenticated member's existing on-chain wallets. "
            + "Does not add a wallet. Returns one status line per source." + SHARED_COOLDOWN_DESCRIPTION)
    @RequiresScope(Scopes.SYNC_TRIGGER)
    public String triggerCryptoWalletSync() {
        return trigger(WALLET_SOURCES);
    }

    @Tool(name = "get_sync_status",
        description = "Read the last sync time, status and reauthentication flag for each existing "
            + "connection the daily scheduler would touch. Does not start a sync.")
    @RequiresScope(Scopes.SYNC_READ)
    public String getSyncStatus() {
        return syncStatusService.describe(userContext.currentMemberId());
    }

    private String trigger(Set<String> sources) {
        Long memberId = userContext.currentMemberId();
        String blocked = cooldownMessage(memberId);
        if (blocked != null) {
            return blocked;
        }
        List<SourceSyncResult> results = sources == null
            ? memberSyncService.resyncForUser(memberId)
            : memberSyncService.resyncForUser(memberId, sources);
        return formatSummary(results);
    }

    private String cooldownMessage(Long memberId) {
        Bucket bucket = memberSyncBuckets.computeIfAbsent(memberId, id -> RateLimitConfig.createMcpMemberSyncBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return null;
        }
        long minutes = Math.max(1, (probe.getNanosToWaitForRefill() + 59_999_999_999L) / 60_000_000_000L);
        return "MCP sync cooldown is shared by all trigger tools for this member. Try again in " + minutes + " min.";
    }

    static String formatSummary(List<SourceSyncResult> results) {
        if (results == null || results.isEmpty()) {
            return "No sync sources ran.";
        }
        StringBuilder text = new StringBuilder();
        for (SourceSyncResult result : results) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(result.source()).append(": ").append(result.status());
            if (result.message() != null && !result.message().isBlank()) {
                text.append(" — ").append(result.message());
            }
        }
        return text.toString();
    }
}
