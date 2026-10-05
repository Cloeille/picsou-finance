package com.picsou.adapter;

import com.picsou.adapter.EvmWalletAdapter.Erc20Token;
import com.picsou.adapter.EvmWalletAdapter.EvmNetwork;
import com.picsou.adapter.EvmWalletAdapter.EvmRpc;
import com.picsou.model.Account;
import com.picsou.model.Chain;
import com.picsou.model.FamilyMember;
import com.picsou.model.WalletAddress;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.WalletAddressRepository;
import com.picsou.service.AccountService;
import com.picsou.service.PriceService;
import com.picsou.service.WalletSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The headline flow, end to end through the real adapter: one {@code 0x} address, seven EVM
 * networks, one account, one holding per symbol.
 *
 * <p>Each layer is covered on its own — {@code EvmWalletAdapterTest} proves the fan-out
 * aggregates by symbol, {@code WalletSyncServiceTest} proves a balance list becomes holdings —
 * but nothing pins the <em>contract between them</em>, which is where this feature's most
 * expensive failure would live. {@code AccountHolding} is unique on
 * {@code (account_id, ticker)}, so if the adapter ever stopped aggregating (returning ETH once
 * per L2 rather than summed) the service would upsert the same ticker repeatedly: either a
 * constraint violation or, worse, each write silently overwriting the last so a wallet holding
 * ETH on four chains reports only the last one's balance.
 *
 * <p>Lives in the adapter package to reach {@code EvmWalletAdapter}'s package-private test
 * constructor; persistence is mocked, so this asserts wiring rather than SQL.
 */
@ExtendWith(MockitoExtension.class)
class EvmWalletSyncFlowTest {

    private static final Long MEMBER_ID = 1L;
    private static final String ADDRESS = "0x1111111111111111111111111111111111111111";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 10^18 wei = 1 coin. */
    private static final String ONE_COIN = result("0xDE0B6B3A7640000");
    /** 500 * 10^6, i.e. 500 units of a 6-decimal token. */
    private static final String FIVE_HUNDRED_6DEC = result("0x1DCD6500");

    @Mock WalletAddressRepository walletRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock PriceService priceService;

    private static String result(String hex) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + hex + "\"}";
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Seven networks mirroring the production registry's shape: shared ETH across L2s. */
    private static List<EvmNetwork> sevenNetworks() {
        Erc20Token usdc = new Erc20Token("0xUSDC", "USDC", 6);
        return List.of(
            new EvmNetwork("Ethereum", "ETH", "https://eth.example", List.of(usdc)),
            new EvmNetwork("Arbitrum", "ETH", "https://arb.example", List.of(usdc)),
            new EvmNetwork("Optimism", "ETH", "https://op.example", List.of(usdc)),
            new EvmNetwork("Base", "ETH", "https://base.example", List.of(usdc)),
            new EvmNetwork("BNB Chain", "BNB", "https://bnb.example", List.of(usdc)),
            new EvmNetwork("Polygon", "POL", "https://poly.example", List.of()),
            new EvmNetwork("Avalanche C-Chain", "AVAX", "https://avax.example", List.of()));
    }

    @Test
    void oneAddressAcrossSevenChains_becomesOneAccountWithOneHoldingPerSymbol() {
        // Every chain answers 1 native coin, and the five with USDC answer 500 each.
        EvmRpc rpc = (url, request) -> Mono.just(parse(
            "eth_call".equals(request.get("method")) ? FIVE_HUNDRED_6DEC : ONE_COIN));
        var adapter = new EvmWalletAdapter(rpc, sevenNetworks());

        WalletAddress wallet = WalletAddress.builder()
            .id(1L).chain(Chain.EVM).address(ADDRESS).build();
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet_evm_1", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(account);
        when(priceService.refreshPrices(any())).thenReturn(Map.of(
            "ETH", new BigDecimal("2000"),
            "BNB", new BigDecimal("500"),
            "POL", new BigDecimal("1"),
            "AVAX", new BigDecimal("30"),
            "USDC", new BigDecimal("1")));

        var service = new WalletSyncService(
            List.of(adapter), walletRepository, accountRepository,
            familyMemberRepository, accountService, priceService);

        service.sync(1L, MEMBER_ID);

        // ONE account, not one per chain.
        verify(accountRepository, times(1)).save(any());

        // One upsert per distinct symbol. A regression that stopped aggregating would call
        // upsertHolding four times for ETH -- colliding on the (account_id, ticker) unique
        // constraint, or silently leaving only the last chain's balance.
        ArgumentCaptor<String> tickers = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> amounts = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountService, times(5)).upsertHolding(
            eq(100L), eq(MEMBER_ID), tickers.capture(), any(), amounts.capture(), any());

        assertThat(tickers.getAllValues())
            .as("one holding per symbol, no duplicates")
            .containsExactlyInAnyOrder("ETH", "BNB", "POL", "AVAX", "USDC");

        Map<String, BigDecimal> held = new java.util.HashMap<>();
        for (int i = 0; i < tickers.getAllValues().size(); i++) {
            held.put(tickers.getAllValues().get(i), amounts.getAllValues().get(i));
        }
        // ETH summed across Ethereum + Arbitrum + Optimism + Base; USDC across five chains.
        assertThat(held.get("ETH")).isEqualByComparingTo("4");
        assertThat(held.get("USDC")).isEqualByComparingTo("2500");
        assertThat(held.get("BNB")).isEqualByComparingTo("1");
        assertThat(held.get("POL")).isEqualByComparingTo("1");
        assertThat(held.get("AVAX")).isEqualByComparingTo("1");

        // The snapshot total isn't asserted here: AccountService.upsertSnapshot is
        // package-private to com.picsou.service and this test must live in the adapter
        // package to reach EvmWalletAdapter's test constructor. The EUR conversion math is
        // covered by WalletSyncServiceTest.sync_persistsHoldingsAndSnapshot_onSuccess; what
        // this test exists for is the aggregation contract above.
    }
}
