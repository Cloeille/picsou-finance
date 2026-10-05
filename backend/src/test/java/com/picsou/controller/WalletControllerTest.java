package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Chain;
import com.picsou.service.UserContext;
import com.picsou.service.WalletSyncService;
import com.picsou.service.WalletSyncService.WalletStatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc) — mirrors
 * {@code AccountControllerLoanTest}, the dominant pattern in this package.
 *
 * <p>The contract worth pinning here is <b>member scoping</b>: every endpoint must pass the id
 * resolved from {@link UserContext}, never one taken from the request. A regression that read a
 * member id off the payload would be a cross-member data leak, and nothing else in the stack
 * would catch it — the service trusts the id it is handed.
 */
@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

    private static final Long MEMBER_ID = 7L;

    @Mock WalletSyncService walletService;
    @Mock UserContext userContext;

    @InjectMocks WalletController controller;

    @Test
    void addWallet_forwardsRequestFieldsAndTheResolvedMemberId() {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        AccountResponse expected = mock(AccountResponse.class);
        when(walletService.addWallet(Chain.EVM, "0xabc", "Ledger", MEMBER_ID)).thenReturn(expected);

        var req = new WalletController.AddWalletRequest(Chain.EVM, "0xabc", "Ledger");

        assertThat(controller.addWallet(req)).isSameAs(expected);
        verify(walletService).addWallet(Chain.EVM, "0xabc", "Ledger", MEMBER_ID);
    }

    @Test
    void addWallet_propagates400WhenServiceRejectsTheAddress() {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        when(walletService.addWallet(any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("Invalid EVM address '0x123'"));

        var req = new WalletController.AddWalletRequest(Chain.EVM, "0x123", null);

        // GlobalExceptionHandler maps IllegalArgumentException to 400.
        assertThatThrownBy(() -> controller.addWallet(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid EVM address");
    }

    @Test
    void sync_forwardsPathIdAndResolvedMemberId() {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        AccountResponse expected = mock(AccountResponse.class);
        when(walletService.sync(42L, MEMBER_ID)).thenReturn(expected);

        assertThat(controller.sync(42L)).isSameAs(expected);
        verify(walletService).sync(42L, MEMBER_ID);
    }

    @Test
    void listWallets_returnsServiceResultForTheResolvedMember() {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        var status = new WalletStatusResponse(1L, Chain.EVM, "0xabc", "Ledger", Instant.EPOCH);
        when(walletService.listWallets(MEMBER_ID)).thenReturn(List.of(status));

        assertThat(controller.listWallets()).containsExactly(status);
        verify(walletService).listWallets(MEMBER_ID);
    }

    @Test
    void removeWallet_returns204AndNoBody() {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);

        ResponseEntity<Void> response = controller.removeWallet(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        verify(walletService).removeWallet(42L, MEMBER_ID);
    }

    @Test
    void removeWallet_propagates404_andReturnsNoSuccessStatus() {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        // Another member's wallet id: the service's member-scoped lookup finds nothing.
        doThrow(new ResourceNotFoundException("Wallet not found"))
            .when(walletService).removeWallet(999L, MEMBER_ID);

        assertThatThrownBy(() -> controller.removeWallet(999L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void endpointsNeverPassAMemberIdFromTheRequest() {
        // Regression guard for the scoping contract: even when the caller supplies ids in the
        // payload/path, the member id must come only from UserContext.
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);

        controller.removeWallet(1L);

        verify(walletService).removeWallet(1L, MEMBER_ID);
        verify(walletService, never()).removeWallet(any(), eq(1L));
    }
}
