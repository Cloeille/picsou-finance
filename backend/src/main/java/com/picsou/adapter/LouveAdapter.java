package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Louve Invest, read through its official MCP server with an OAuth token the member granted on
 * Louve's own consent page. No sidecar, no scraping, no password: the session state is the token
 * set, serialized here and stored encrypted by the caller.
 *
 * <p>Louve rotates refresh tokens: a refresh answers a new refresh token and invalidates the old
 * one. {@link #refresh} returns the new state; the caller persists it, under the connection's row
 * lock so two runs never refresh the same token, before {@link #read} uses it. A lost rotation
 * would force the member to reconnect.
 *
 * <p>This class only speaks the protocol and returns what Louve said. Which figures become a
 * balance is decided by {@code LouvePortfolioMapper}.
 */
@Component
public class LouveAdapter {

    /** An access token this close to its expiry is refreshed before the run, not halfway through. */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);
    /** A portfolio is a handful of funds; this only bounds the {@code get_scpi} calls of a bad answer. */
    static final int MAX_POSITIONS = 200;

    private static final Logger log = LoggerFactory.getLogger(LouveAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int STATE_VERSION = 1;

    private final LouveOAuthClient oauth;
    private final LouveMcpClient mcp;
    private final Clock clock;

    @Autowired
    public LouveAdapter(LouveOAuthClient oauth, LouveMcpClient mcp, Clock clock) {
        this.oauth = oauth;
        this.mcp = mcp;
        this.clock = clock;
    }

    /** One line of {@code get_portfolio.scpi.positions}. {@code sellingTotal} is for the whole line. */
    public record Position(
        String fundCode,
        String label,
        BigDecimal shareCount,
        BigDecimal sellingTotal,
        List<String> ownership
    ) {}

    /**
     * What {@code get_scpi} says about one fund, per share. Null fields were absent. {@code isinCode}
     * is Louve's raw code, which is not always an ISIN.
     */
    public record Fund(
        String fundCode,
        String name,
        String isinCode,
        String managementCompany,
        BigDecimal subscriptionPrice,
        BigDecimal withdrawalPrice,
        BigDecimal distributionRate,
        String rentFrequency
    ) {}

    /**
     * {@code declaredSellingTotal} is {@code get_portfolio.scpi.sellingPrice}, the envelope the
     * lines must add up to. {@code funds} is keyed by fund code; a fund whose sheet Louve could not
     * serve is absent.
     */
    public record Snapshot(BigDecimal declaredSellingTotal, List<Position> positions, Map<String, Fund> funds) {}

    // -- connection ------------------------------------------------------------------------

    /** Exchanges the code and returns the session state to store (encrypted) for the member. */
    public String exchangeCode(String clientId, String redirectUri, String code, String verifier) {
        LouveOAuthClient.Tokens tokens = oauth.exchangeCode(clientId, redirectUri, code, verifier);
        if (tokens.refreshToken() == null) {
            // Without one, the connection would silently die after an hour.
            throw LouveMcpClient.formatChanged("Louve Invest granted no lasting access");
        }
        return serialize(clientId, redirectUri, tokens.accessToken(), tokens.refreshToken(), expiry(tokens));
    }

    /** Revokes the grant behind a stored state. Best effort, never throws. */
    public void revoke(String plainState) {
        TokenState state;
        try {
            state = parse(plainState);
        } catch (RuntimeException ex) {
            return;
        }
        // Revoking the refresh token ends the grant; the access token is revoked too in case
        // Louve treats them separately.
        oauth.revoke(state.clientId(), state.refreshToken(), "refresh_token");
        oauth.revoke(state.clientId(), state.accessToken(), "access_token");
    }

    // -- read ------------------------------------------------------------------------------

    /** Whether the access token expires within {@link #EXPIRY_MARGIN}, so the run refreshes first. */
    public boolean needsRefresh(String plainState) {
        return !parse(plainState).expiresAt().isAfter(clock.instant().plus(EXPIRY_MARGIN));
    }

    /**
     * Reads the SCPI portfolio with the state's access token. A refused token is
     * {@link LouveMcpClient.Unauthorized}: the caller refreshes once and reads again.
     */
    public Snapshot read(String plainState) {
        return readWith(parse(plainState).accessToken());
    }

    private Snapshot readWith(String accessToken) {
        LouveMcpClient.Session session = mcp.open(accessToken);
        // includeExternal=false: external lines are SCPIs the member declared on Louve as held
        // elsewhere. Louve does not hold them, and the member may already sync them from that place.
        JsonNode portfolio = session.callTool("get_portfolio", Map.of("includeExternal", false));
        if (!portfolio.has("scpi")) {
            // No "scpi" key at all is not "no SCPI": it is another shape, and reading it as an
            // empty portfolio would report every known fund as missing.
            throw LouveMcpClient.formatChanged("Louve Invest returned a portfolio without its SCPI side");
        }
        JsonNode scpi = portfolio.get("scpi");
        if (scpi.isNull()) {
            // An explicit null SCPI side: an account holding only life insurance, say.
            return new Snapshot(BigDecimal.ZERO, List.of(), Map.of());
        }
        JsonNode lines = scpi.get("positions");
        if (!scpi.isObject() || lines == null || !lines.isArray()) {
            throw LouveMcpClient.formatChanged("Louve Invest returned a portfolio without its SCPI positions");
        }
        if (lines.size() > MAX_POSITIONS) {
            throw new SyncException("Louve Invest returned too many positions", null,
                SidecarErrorCode.INVALID_DATA.name());
        }
        List<Position> positions = new ArrayList<>();
        for (JsonNode line : lines) {
            if (!line.isObject()) {
                throw LouveMcpClient.formatChanged("Louve Invest returned an unreadable SCPI position");
            }
            List<String> ownership = new ArrayList<>();
            JsonNode owned = line.get("ownership");
            // Required, and an array of strings: missing or null is another format, not
            // "dismembered". Live data always carries ["Pleine propriété"].
            if (owned == null || !owned.isArray()) {
                throw LouveMcpClient.formatChanged("Louve Invest returned an unreadable ownership");
            }
            for (JsonNode item : owned) {
                // Every element is checked, none dropped: filtering an object out of
                // ["Pleine propriété", {...}] would value a dismembered line as full ownership.
                if (!item.isTextual()) {
                    throw LouveMcpClient.formatChanged("Louve Invest returned an unreadable ownership");
                }
                ownership.add(item.asText());
            }
            positions.add(new Position(text(line, "scpiUrlId"), text(line, "scpiName"),
                decimal(line, "nbShares"), decimal(line, "sellingPrice"), List.copyOf(ownership)));
        }
        Map<String, Fund> funds = new LinkedHashMap<>();
        for (Position position : positions) {
            String code = position.fundCode();
            if (code == null || funds.containsKey(code) || !LouvePortfolioCodes.isFundCode(code)) {
                continue;
            }
            try {
                funds.put(code, fund(code, session.callTool("get_scpi", Map.of("scpi", code))));
            } catch (LouveMcpClient.ToolFailed ex) {
                // The portfolio line still carries its own value; the mapper decides without the sheet.
                log.warn("Louve Invest could not serve a fund sheet (fund={})", code);
            }
        }
        return new Snapshot(decimal(scpi, "sellingPrice"), List.copyOf(positions), Map.copyOf(funds));
    }

    private static Fund fund(String code, JsonNode sheet) {
        String urlId = text(sheet, "urlId");
        if (urlId == null) {
            throw LouveMcpClient.formatChanged("Louve Invest returned a fund sheet without its identifier");
        }
        if (!urlId.equals(code)) {
            throw new SyncException("Louve Invest answered another fund than the one asked for", null,
                SidecarErrorCode.INVALID_DATA.name());
        }
        return new Fund(code, text(sheet, "name"), text(sheet, "isinCode"), text(sheet, "managementCompany"),
            decimal(sheet, "buyingPriceByShare"), decimal(sheet, "sellingPriceByShare"),
            decimal(sheet, "distributionRate"), text(sheet, "rentFrequency"));
    }

    // -- token state -----------------------------------------------------------------------

    /**
     * Refreshes the access token and returns the new state, which the caller must persist before
     * using it: Louve has already invalidated the old refresh token. A refused client carries the
     * redirect URI its registration was made for.
     */
    public String refresh(String plainState) {
        TokenState state = parse(plainState);
        LouveOAuthClient.Tokens tokens;
        try {
            tokens = oauth.refresh(state.clientId(), state.refreshToken());
        } catch (LouveOAuthClient.InvalidClientException ex) {
            // The refresh request carries no redirect URI; the stored state knows which
            // registration the refused client belongs to.
            throw ex.withRedirectUri(state.redirectUri());
        }
        // Louve rotates: keep the old refresh token only if none came back.
        String refreshToken = tokens.refreshToken() != null ? tokens.refreshToken() : state.refreshToken();
        return serialize(state.clientId(), state.redirectUri(), tokens.accessToken(), refreshToken, expiry(tokens));
    }

    private Instant expiry(LouveOAuthClient.Tokens tokens) {
        return clock.instant().plusSeconds(tokens.expiresInSeconds());
    }

    /** {@code redirectUri} names the registration {@code clientId} belongs to, so a refused client can be forgotten. */
    record TokenState(String clientId, String redirectUri, String accessToken, String refreshToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "TokenState[expiresAt=" + expiresAt + "]";
        }
    }

    static String serialize(String clientId, String redirectUri, String accessToken, String refreshToken,
                            Instant expiresAt) {
        ObjectNode node = JSON.createObjectNode();
        node.put("v", STATE_VERSION);
        node.put("clientId", clientId);
        node.put("redirectUri", redirectUri);
        node.put("accessToken", accessToken);
        node.put("refreshToken", refreshToken);
        node.put("expiresAt", expiresAt.getEpochSecond());
        return node.toString();
    }

    /**
     * The stored state, or {@code INVALID_SESSION_STATE}: an unreadable token set can never be
     * renewed. Every field is required, the redirect URI included.
     */
    static TokenState parse(String plainState) {
        try {
            JsonNode node = plainState == null ? null : JSON.readTree(plainState);
            if (node != null && node.path("v").asInt() == STATE_VERSION
                    && text(node, "clientId") != null && text(node, "redirectUri") != null
                    && text(node, "accessToken") != null
                    && text(node, "refreshToken") != null && node.path("expiresAt").canConvertToLong()) {
                return new TokenState(text(node, "clientId"), text(node, "redirectUri"), text(node, "accessToken"),
                    text(node, "refreshToken"), Instant.ofEpochSecond(node.get("expiresAt").asLong()));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Falls through to the same refusal; the content is never logged.
        }
        throw new SyncException("The stored Louve Invest access is unusable. Please reconnect.", null,
            SidecarErrorCode.INVALID_SESSION_STATE.name());
    }

    // -- JSON helpers ----------------------------------------------------------------------

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    /** A JSON number (or a numeric string) as a decimal; null when absent or unreadable. */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
