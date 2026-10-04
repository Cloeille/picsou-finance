package com.picsou.mcp.tools;

import com.picsou.service.BoursoSyncService;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.SyncService;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.service.UserContext;
import com.picsou.service.WalletSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sync-trigger tools only ever <em>refresh existing</em> connections — the exact resync methods the
 * daily scheduler uses — scoped to the authenticated member. They never initiate a new connection or
 * re-authenticate (those flows are deliberately absent from the MCP surface).
 */
@ExtendWith(MockitoExtension.class)
class SyncToolsTest {

    private static final long MID = 7L;

    @Mock SyncService syncService;
    @Mock TradeRepublicSyncService tradeRepublicSyncService;
    @Mock BoursoSyncService boursoSyncService;
    @Mock com.picsou.service.AmexSyncService amexSyncService;
    @Mock CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock WalletSyncService walletSyncService;
    @Mock UserContext userContext;
    @InjectMocks SyncTools tools;

    @Test
    void triggerBankSync_resyncsExistingBankConnectionsForCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.triggerBankSync();

        verify(syncService).resyncAll(MID);
    }

    @Test
    void triggerBrokerSync_resyncsTradeRepublicAndBoursoForCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.triggerBrokerSync();

        verify(tradeRepublicSyncService).resyncIfSessionActive(MID);
        verify(boursoSyncService).resyncIfSessionActive(MID);
    }

    @Test
    void triggerCryptoExchangeSync_resyncsExchangesForCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.triggerCryptoExchangeSync();

        verify(cryptoExchangeSyncService).resyncAll(MID);
    }

    @Test
    void triggerCryptoWalletSync_resyncsWalletsForCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);
        when(walletSyncService.resyncAll(MID))
            .thenReturn(new WalletSyncService.ResyncSummary(1, 1, java.util.List.of()));

        tools.triggerCryptoWalletSync();

        verify(walletSyncService).resyncAll(MID);
    }
}
