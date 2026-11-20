package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Synthetic tokens and portfolios. */
class LouveAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");
    private static final String REDIRECT = "https://picsou.example/sync/louve/callback";
    private static final String PORTFOLIO = """
        {"date":"2026-11-17","scpi":{"sellingPrice":1920,"positions":[
          {"scpiUrlId":"aurore","scpiName":"Aurore","nbShares":12,"sellingPrice":960,"ownership":["Pleine propriété"]},
          {"scpiUrlId":"explore","scpiName":"Explore","nbShares":5,"sellingPrice":960,"ownership":["Pleine propriété"]}]}}
        """;
    private static final String AURORE_SHEET = """
        {"name":"Aurore","urlId":"aurore","isinCode":"SCPI99990001","managementCompany":"Exemple REIM",
         "buyingPriceByShare":80,"sellingPriceByShare":80,"distributionRate":0.08,"rentFrequency":"monthly"}
        """;

    private LouveOAuthClient oauth;
    private LouveMcpClient mcp;
    private LouveMcpClient.Session session;
    private LouveAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        oauth = mock(LouveOAuthClient.class);
        mcp = mock(LouveMcpClient.class);
        session = mock(LouveMcpClient.Session.class);
        adapter = new LouveAdapter(oauth, mcp, Clock.fixed(NOW, ZoneOffset.UTC));
        when(mcp.open(anyString())).thenReturn(session);
        when(session.callTool(eq("get_portfolio"), anyMap())).thenReturn(JSON.readTree(PORTFOLIO));
        when(session.callTool("get_scpi", Map.of("scpi", "aurore"))).thenReturn(JSON.readTree(AURORE_SHEET));
        when(session.callTool("get_scpi", Map.of("scpi", "explore")))
            .thenThrow(new LouveMcpClient.ToolFailed("get_scpi"));
    }

    private static String state(Instant expiresAt) {
        return LouveAdapter.serialize("cid", REDIRECT, "access-old", "refresh-old", expiresAt);
    }

    @Test
    void aReadUsesTheStoredAccessTokenAndEachFundSheet() {
        LouveAdapter.Snapshot snapshot = adapter.read(state(NOW.plusSeconds(3000)));

        verify(mcp).open("access-old");
        verify(oauth, never()).refresh(anyString(), anyString());
        verify(session).callTool("get_portfolio", Map.of("includeExternal", false));
        assertThat(snapshot.declaredSellingTotal()).isEqualByComparingTo("1920");
        assertThat(snapshot.positions()).extracting(LouveAdapter.Position::fundCode).containsExactly("aurore", "explore");
        assertThat(snapshot.positions().get(0).ownership()).containsExactly("Pleine propriété");
        // A sheet Louve could not serve is left out, not fatal.
        assertThat(snapshot.funds()).containsOnlyKeys("aurore");
        LouveAdapter.Fund aurore = snapshot.funds().get("aurore");
        assertThat(aurore.managementCompany()).isEqualTo("Exemple REIM");
        assertThat(aurore.withdrawalPrice()).isEqualByComparingTo("80");
        assertThat(aurore.distributionRate()).isEqualByComparingTo("0.08");
        assertThat(aurore.rentFrequency()).isEqualTo("monthly");
    }

    @Test
    void aTokenCloseToItsExpiryNeedsARefresh() {
        assertThat(adapter.needsRefresh(state(NOW.plusSeconds(3000)))).isFalse();
        assertThat(adapter.needsRefresh(state(NOW.plusSeconds(30)))).isTrue();
        assertThat(adapter.needsRefresh(state(NOW.minusSeconds(1)))).isTrue();
    }

    @Test
    void aRefreshReturnsTheRotatedSetWithoutReadingAnything() {
        when(oauth.refresh("cid", "refresh-old"))
            .thenReturn(new LouveOAuthClient.Tokens("access-new", "refresh-new", 3600, "portfolio:read"));

        LouveAdapter.TokenState next = LouveAdapter.parse(adapter.refresh(state(NOW)));

        assertThat(next.refreshToken()).isEqualTo("refresh-new");
        assertThat(next.accessToken()).isEqualTo("access-new");
        assertThat(next.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(next.redirectUri()).isEqualTo(REDIRECT);
        verify(mcp, never()).open(anyString());
    }

    @Test
    void aRefreshWithoutANewRefreshTokenKeepsTheOldOne() {
        when(oauth.refresh("cid", "refresh-old"))
            .thenReturn(new LouveOAuthClient.Tokens("access-new", null, 3600, null));

        assertThat(LouveAdapter.parse(adapter.refresh(state(NOW))).refreshToken()).isEqualTo("refresh-old");
    }

    @Test
    void aRefusedAccessTokenIsUnauthorizedForTheCallerToRefresh() {
        when(mcp.open("access-old")).thenThrow(new LouveMcpClient.Unauthorized());

        assertThatThrownBy(() -> adapter.read(state(NOW.plusSeconds(3000))))
            .isInstanceOf(LouveMcpClient.Unauthorized.class);
        verify(oauth, never()).refresh(anyString(), anyString());
    }

    @Test
    void aClientRefusedOnRefreshNamesTheRegistrationItBelongsTo() {
        when(oauth.refresh("cid", "refresh-old")).thenThrow(new LouveOAuthClient.InvalidClientException(
            "refused", SidecarErrorCode.SESSION_EXPIRED, "cid", null));

        assertThatThrownBy(() -> adapter.refresh(state(NOW)))
            .isInstanceOfSatisfying(LouveOAuthClient.InvalidClientException.class, ex -> {
                assertThat(ex.clientId()).isEqualTo("cid");
                assertThat(ex.redirectUri()).isEqualTo(REDIRECT);
                assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED.name());
            });
    }

    @Test
    void aRevokedRefreshTokenIsSessionExpired() {
        when(oauth.refresh("cid", "refresh-old"))
            .thenThrow(new SyncException("revoked", null, SidecarErrorCode.SESSION_EXPIRED.name()));

        assertThatThrownBy(() -> adapter.refresh(state(NOW)))
            .isInstanceOfSatisfying(SyncException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED.name()));
    }

    @Test
    void anUnreadableStateIsInvalidSessionState() {
        assertThatThrownBy(() -> adapter.read("not json"))
            .isInstanceOfSatisfying(SyncException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.INVALID_SESSION_STATE.name()));
        assertThatThrownBy(() -> LouveAdapter.parse("{\"v\":1,\"clientId\":\"c\"}"))
            .isInstanceOf(SyncException.class);
    }

    @Test
    void aStateWithoutItsRedirectUriIsUnreadable() {
        String withoutRedirect = "{\"v\":1,\"clientId\":\"cid\",\"accessToken\":\"a\",\"refreshToken\":\"r\","
            + "\"expiresAt\":" + NOW.plusSeconds(3000).getEpochSecond() + "}";

        assertThatThrownBy(() -> adapter.read(withoutRedirect))
            .isInstanceOfSatisfying(SyncException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.INVALID_SESSION_STATE.name()));
        assertThatThrownBy(() -> adapter.refresh(withoutRedirect)).isInstanceOf(SyncException.class);
        verify(mcp, never()).open(anyString());
    }

    @Test
    void aSheetForAnotherFundIsRefused() throws Exception {
        when(session.callTool("get_scpi", Map.of("scpi", "aurore")))
            .thenReturn(JSON.readTree("{\"urlId\":\"other\"}"));

        assertThatThrownBy(() -> adapter.read(state(NOW.plusSeconds(3000))))
            .isInstanceOfSatisfying(SyncException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.INVALID_DATA.name()));
    }

    @Test
    void anExplicitlyNullScpiSideIsEmpty() throws Exception {
        when(session.callTool(eq("get_portfolio"), anyMap())).thenReturn(JSON.readTree("{\"scpi\":null}"));

        LouveAdapter.Snapshot snapshot = adapter.read(state(NOW.plusSeconds(3000)));

        assertThat(snapshot.positions()).isEmpty();
    }

    @Test
    void theCodeExchangeNeedsARefreshToken() {
        when(oauth.exchangeCode("cid", "r", "c", "v"))
            .thenReturn(new LouveOAuthClient.Tokens("at", null, 3600, null));

        assertThatThrownBy(() -> adapter.exchangeCode("cid", "r", "c", "v")).isInstanceOf(SyncException.class);
    }

    @Test
    void revokingAnUnreadableStateDoesNothing() {
        adapter.revoke("garbage");
        adapter.revoke(state(NOW));

        verify(oauth).revoke("cid", "refresh-old", "refresh_token");
        verify(oauth).revoke("cid", "access-old", "access_token");
    }
}
