package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An extended key the adapter cannot parse must fail the sync, not report a balance of zero.
 * Nothing here reaches the network: the key is rejected before the first Esplora call, which is
 * exactly the point, since a returned zero used to be written into the account and its snapshot.
 */
class BitcoinWalletAdapterTest {

    @Test
    void anInvalidExtendedKey_throws_ratherThanReportingZeroBtc() {
        BitcoinWalletAdapter adapter = new BitcoinWalletAdapter();

        assertThatThrownBy(() -> adapter.fetchBalances("xpub6InvalidKeyThatIsFarTooShort"))
            .isInstanceOf(RuntimeException.class);
    }
}
