package com.picsou.mcp.tools;

import com.picsou.config.RateLimitConfig;
import com.picsou.service.MemberSyncService;
import com.picsou.service.SyncStatusService;
import com.picsou.service.UserContext;
import com.picsou.service.sync.SourceSyncResult;
import com.picsou.service.sync.SourceSyncResult.Status;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncToolsTest {

    private static final long MID = 7L;

    @Mock MemberSyncService memberSyncService;
    @Mock SyncStatusService syncStatusService;
    @Mock UserContext userContext;

    private Map<Long, Bucket> buckets;
    private SyncTools tools;

    @BeforeEach
    void setUp() {
        buckets = new HashMap<>();
        tools = new SyncTools(memberSyncService, syncStatusService, userContext, buckets);
        when(userContext.currentMemberId()).thenReturn(MID);
    }

    @Test
    void triggerFullSync_returnsEverySourceAndNamesFailures() {
        when(memberSyncService.resyncForUser(MID)).thenReturn(List.of(
            new SourceSyncResult("revolut", Status.SYNCED, ""),
            new SourceSyncResult("bourso", Status.NEEDS_REAUTH, "session expired"),
            new SourceSyncResult("ibkr", Status.FAILED, "timeout")
        ));

        String text = tools.triggerFullSync();

        assertThat(text).contains("revolut: SYNCED");
        assertThat(text).contains("bourso: NEEDS_REAUTH — session expired");
        assertThat(text).contains("ibkr: FAILED — timeout");
        assertThat(text).doesNotContain("triggered");
        verify(memberSyncService).resyncForUser(MID);
        verify(memberSyncService, never()).resyncForUser(eq(MID), any());
    }

    @Test
    void triggerBankSync_filtersEnableBankingThroughTheSharedMethod() {
        when(memberSyncService.resyncForUser(MID, SyncTools.BANK_SOURCES))
            .thenReturn(List.of(new SourceSyncResult("enable-banking", Status.FAILED, "denied")));

        String text = tools.triggerBankSync();

        assertThat(text).contains("enable-banking: FAILED — denied");
        verify(memberSyncService).resyncForUser(MID, Set.of("enable-banking", "enable-banking-retry"));
    }

    @Test
    void triggerBrokerSync_coversEveryBrokerTheSchedulerOwns() {
        when(memberSyncService.resyncForUser(MID, SyncTools.BROKER_SOURCES)).thenReturn(List.of(
            new SourceSyncResult("trade-republic", Status.SYNCED, ""),
            new SourceSyncResult("degiro", Status.SKIPPED_NOT_CONNECTED, "No session")
        ));

        tools.triggerBrokerSync();

        verify(memberSyncService).resyncForUser(MID, Set.of(
            "trade-republic", "bourso", "bourse-direct", "amundi", "fortuneo", "ibkr", "degiro"));
    }

    @Test
    void triggerCryptoExchangeSync_filtersTheExchangeSource() {
        when(memberSyncService.resyncForUser(MID, SyncTools.EXCHANGE_SOURCES))
            .thenReturn(List.of(new SourceSyncResult("crypto-exchanges", Status.SYNCED, "")));

        tools.triggerCryptoExchangeSync();

        verify(memberSyncService).resyncForUser(MID, Set.of("crypto-exchanges"));
    }

    @Test
    void triggerCryptoWalletSync_filtersTheWalletSource() {
        when(memberSyncService.resyncForUser(MID, SyncTools.WALLET_SOURCES))
            .thenReturn(List.of(new SourceSyncResult("wallets", Status.SYNCED, "")));

        tools.triggerCryptoWalletSync();

        verify(memberSyncService).resyncForUser(MID, Set.of("wallets"));
    }

    @Test
    void secondTrigger_isBlockedAndDoesNotCallTheBanks() {
        when(memberSyncService.resyncForUser(MID))
            .thenReturn(List.of(new SourceSyncResult("revolut", Status.SYNCED, "")));

        tools.triggerFullSync();
        String blocked = tools.triggerBankSync();

        assertThat(blocked).startsWith("Sync already ran for this member. Try again in ");
        assertThat(blocked).endsWith(" min.");
        assertThat(blocked).containsPattern("Try again in \\d+ min\\.");
        verify(memberSyncService).resyncForUser(MID);
        verify(memberSyncService, never()).resyncForUser(eq(MID), any());
    }

    @Test
    void drainedBucket_reportsAtLeastOneMinute() {
        Bucket drained = RateLimitConfig.createMcpMemberSyncBucket();
        drained.tryConsume(1);
        buckets.put(MID, drained);

        String blocked = tools.triggerFullSync();

        assertThat(blocked).contains("Try again in");
        assertThat(blocked).contains("min.");
        verify(memberSyncService, never()).resyncForUser(any());
        verify(memberSyncService, never()).resyncForUser(any(), any());
    }

    @Test
    void getSyncStatus_doesNotConsumeTheCooldown() {
        when(syncStatusService.describe(MID)).thenReturn("revolut: CONNECTED lastSync=none reauth=false");

        assertThat(tools.getSyncStatus()).contains("reauth=false");
        tools.getSyncStatus();
        tools.triggerFullSync();

        verify(syncStatusService, org.mockito.Mockito.times(2)).describe(MID);
        verify(memberSyncService).resyncForUser(MID);
    }
}
