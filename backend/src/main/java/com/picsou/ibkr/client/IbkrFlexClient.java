package com.picsou.ibkr.client;

import com.picsou.exception.SyncException;
import com.picsou.util.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interactive Brokers Flex Web Service client (Open Positions).
 *
 * <p>Implements the two-step Flex flow with the JDK's own {@link HttpClient} and DOM
 * parser — no new dependency, mirroring {@code FinaryApiClient}'s use of raw
 * {@code HttpClient}:
 * <ol>
 *   <li>{@code GET /SendRequest?t=<token>&q=<queryId>&v=3} → {@code <FlexStatementResponse>}
 *       with {@code <Status>Success</Status>} and a {@code <ReferenceCode>};</li>
 *   <li>{@code GET /GetStatement?t=<token>&q=<referenceCode>&v=3} → either the
 *       {@code <FlexQueryResponse>} payload, or a {@code <FlexStatementResponse>} whose
 *       status says generation is still in progress (poll again), or an error.</li>
 * </ol>
 *
 * <p>IBKR rate-limits Flex requests to roughly one per second per token and only
 * refreshes Activity-statement data once a day, so bounded polling with backoff is
 * sufficient and deliberate.
 */
@Component
@Slf4j
public class IbkrFlexClient {

    /** What an IBKR account id looks like (U1234567, DU123456, an advisor sub-account U123456.1). */
    private static final java.util.regex.Pattern ACCOUNT_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private static final String BASE_URL =
        "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService";
    private static final String VERSION = "3";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** How many times to poll GetStatement while IBKR is still generating the report. */
    private static final int MAX_POLLS = 8;
    /** Base wait between polls; IBKR needs a few seconds and rate-limits to ~1 req/s. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .build();

    /**
     * Runs the Open Positions Flex Query and returns the current positions grouped
     * by IBKR account. Blocks while IBKR generates the statement (bounded polling).
     *
     * @param token   Flex Web Service token (plaintext; the caller decrypts it)
     * @param queryId Flex Query id for the Open Positions query
     * @return one {@link IbkrAccountData} per IBKR account id found in the statement
     */
    public List<IbkrAccountData> fetchOpenPositions(String token, String queryId) {
        String referenceCode = sendRequest(token, queryId);
        String statementXml = pollStatement(token, referenceCode);
        return parseOpenPositions(statementXml);
    }

    // ---------------------------------------------------------------------------
    // Step 1: SendRequest → reference code
    // ---------------------------------------------------------------------------

    private String sendRequest(String token, String queryId) {
        String url = BASE_URL + "/SendRequest?t=" + enc(token) + "&q=" + enc(queryId) + "&v=" + VERSION;
        Document doc = parse(get(url));
        String root = doc.getDocumentElement().getNodeName();

        // A well-formed SendRequest reply is always a FlexStatementResponse.
        String status = childText(doc.getDocumentElement(), "Status");
        if ("Success".equalsIgnoreCase(status)) {
            String referenceCode = childText(doc.getDocumentElement(), "ReferenceCode");
            if (referenceCode == null || referenceCode.isBlank()) {
                throw new SyncException("IBKR did not return a reference code. Check your token and query id.");
            }
            log.info("IBKR Flex SendRequest OK, reference code {}", referenceCode);
            return referenceCode;
        }
        throw failFromError(doc.getDocumentElement(),
            "IBKR rejected the Flex request (root=" + root + ", status=" + status + ")");
    }

    // ---------------------------------------------------------------------------
    // Step 2: GetStatement (poll until ready) → statement XML
    // ---------------------------------------------------------------------------

    private String pollStatement(String token, String referenceCode) {
        String url = BASE_URL + "/GetStatement?t=" + enc(token) + "&q=" + enc(referenceCode) + "&v=" + VERSION;

        for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
            String body = get(url);
            Document doc = parse(body);
            String root = doc.getDocumentElement().getNodeName();

            // Ready: the payload is a FlexQueryResponse.
            if ("FlexQueryResponse".equals(root)) {
                return body;
            }

            // Otherwise it is a FlexStatementResponse — either "still generating" (poll again)
            // or a hard error (token expired, invalid query, ...).
            String status = childText(doc.getDocumentElement(), "Status");
            if (isGenerationInProgress(doc.getDocumentElement())) {
                log.info("IBKR statement not ready yet (attempt {}/{}), waiting {}s",
                    attempt + 1, MAX_POLLS, POLL_INTERVAL.toSeconds());
                sleep(POLL_INTERVAL.multipliedBy(1L + attempt / 2)); // gentle backoff
                continue;
            }
            throw failFromError(doc.getDocumentElement(),
                "IBKR could not deliver the statement (root=" + root + ", status=" + status + ")");
        }
        throw new SyncException("IBKR statement was still generating after "
            + MAX_POLLS + " attempts. Please try again in a minute.");
    }

    /**
     * True when a FlexStatementResponse says the report is still being generated.
     * IBKR signals this with error code 1019, but we also match the message text so a
     * change in the numeric code does not turn a transient "not ready" into a hard failure.
     */
    private boolean isGenerationInProgress(Element root) {
        String code = childText(root, "ErrorCode");
        if ("1019".equals(code)) {
            return true;
        }
        String message = childText(root, "ErrorMessage");
        if (message == null) {
            return false;
        }
        // Deliberately broad substring matching ("generat" covers "generating"/"generation"):
        // forward compatibility with rewordings of the transient not-ready message.
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("in progress") || lower.contains("try again") || lower.contains("generat");
    }

    private SyncException failFromError(Element root, String context) {
        String code = childText(root, "ErrorCode");
        String message = childText(root, "ErrorMessage");
        String detail = message != null ? message : context;
        log.error("IBKR Flex error (code={}, message={})", code, message);
        // Surface IBKR's own message — it is descriptive (expired token, invalid query id, ...).
        return new SyncException("Interactive Brokers: " + detail
            + (code != null ? " (code " + code + ")" : ""));
    }

    // ---------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------

    /**
     * Parses {@code <OpenPosition>} rows out of a FlexQueryResponse, grouped by
     * IBKR account id, preserving document order within each account.
     */
    // Public so a statement the user downloaded from Client Portal goes through the very same
    // parser as one fetched from the Flex Web Service (IbkrSyncService#importStatement), and so
    // IbkrFlexClientTest can exercise it directly against a fixture, without any HTTP. This is
    // the single riskiest part (exact IBKR attribute names), so it is the part most worth a
    // fixture test.
    public List<IbkrAccountData> parseOpenPositions(String xml) {
        return parseOpenPositions(parse(xml));
    }

    /**
     * Same parser, for a Flex report the user uploaded. It takes the raw bytes so the XML parser
     * detects the encoding itself (a BOM, UTF-16, a declared ISO-8859-1) instead of a UTF-8 decode
     * that would corrupt the others, and a file that does not parse is reported as such without
     * echoing any of its content into the logs.
     */
    public List<IbkrAccountData> parseUploadedStatement(byte[] xml) {
        Document doc = parse(xml, true);
        requireConsistentAccounts(doc);
        requireOpenPositionsSection(doc);
        return parseOpenPositions(doc);
    }

    /**
     * An account id becomes an account name and an external id, so an uploaded file's ids must look like
     * IBKR's, and every position must belong to a statement of the file: a position with another id would
     * parse to an account of its own, with no date, and be refused for a reason the user cannot act on.
     */
    private void requireConsistentAccounts(Document doc) {
        Set<String> statementIds = new HashSet<>();
        NodeList statements = doc.getElementsByTagName("FlexStatement");
        for (int i = 0; i < statements.getLength(); i++) {
            String id = blankToNull(attr((Element) statements.item(i), "accountId"));
            if (id == null) {
                continue;
            }
            if (!ACCOUNT_ID.matcher(id).matches()) {
                throw new SyncException("This file carries an account id Picsou does not recognise as an "
                    + "Interactive Brokers one. Check that it is the report of your Flex Query.");
            }
            statementIds.add(id);
        }
        NodeList positions = doc.getElementsByTagName("OpenPosition");
        for (int i = 0; i < positions.getLength(); i++) {
            String id = blankToNull(attr((Element) positions.item(i), "accountId"));
            if (id == null || !statementIds.contains(id)) {
                throw new SyncException("This report lists positions that belong to no account statement of the "
                    + "file. Check that it is the unedited report of your Flex Query.");
            }
        }
    }

    /**
     * Refuses a report that has no {@code <OpenPositions>} section for an account. Accounts are
     * seeded from every {@code <FlexStatement>}, so a Trades, NAV or Cash report of a known account
     * would otherwise parse to an account with no position, and importing it would empty that account.
     * An empty {@code <OpenPositions/>} stays valid: that is how a liquidated account reports.
     */
    private void requireOpenPositionsSection(Document doc) {
        NodeList statements = doc.getElementsByTagName("FlexStatement");
        for (int i = 0; i < statements.getLength(); i++) {
            Element statement = (Element) statements.item(i);
            // A statement without an account id is skipped by the parser, so it cannot empty anything.
            if (blankToNull(attr(statement, "accountId")) != null
                && statement.getElementsByTagName("OpenPositions").getLength() == 0) {
                throw new SyncException("This report has no Open Positions section for account "
                    + LogSanitizer.safe(attr(statement, "accountId")) + ". Add the Open Positions section to "
                    + "the Flex Query and download the report again; importing it as is would empty the account.");
            }
        }
    }

    private List<IbkrAccountData> parseOpenPositions(Document doc) {
        NodeList positions = doc.getElementsByTagName("OpenPosition");

        // Account base currency, keyed by accountId -- from the optional
        // AccountInformation section (enabled per Flex Query, sibling of OpenPositions
        // under FlexStatement). Absent entirely on statements that don't enable it.
        Map<String, String> baseCurrencyByAccount = parseAccountInformation(doc);

        // LinkedHashMap: stable account ordering for deterministic downstream upserts.
        // Accounts are seeded from the <FlexStatement accountId="..."> elements, which are
        // present even when the account holds no open position (fully liquidated
        // portfolio). Deriving accounts from <OpenPosition> rows alone made an emptied
        // account vanish from the result, so its stale holdings were never purged and the
        // dashboard kept valuing positions the user no longer owns.
        Map<String, List<IbkrPosition>> byAccount = new LinkedHashMap<>();
        Map<String, LocalDate> dateByAccount = new HashMap<>();
        NodeList statements = doc.getElementsByTagName("FlexStatement");
        for (int i = 0; i < statements.getLength(); i++) {
            Node node = statements.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String stmtAccount = blankToNull(attr((Element) node, "accountId"));
            if (stmtAccount == null) {
                continue;
            }
            if (byAccount.containsKey(stmtAccount)) {
                // Several FlexStatement sections for the SAME account = a multi-day query
                // ("Breakout by Day" / date-ranged period): each day carries a full
                // positions snapshot, and merging them would silently multiply every
                // quantity by the number of days. Fail with the remedy instead.
                throw new SyncException("The Flex statement contains several sections for account "
                    + LogSanitizer.safe(stmtAccount) + " — set the Flex Query period to 'Last Business Day' "
                    + "(and disable any day-by-day breakout) so positions are a single snapshot.");
            }
            byAccount.put(stmtAccount, new ArrayList<>());
            LocalDate toDate = statementDate((Element) node);
            if (toDate != null) {
                dateByAccount.put(stmtAccount, toDate);
            }
        }

        for (int i = 0; i < positions.getLength(); i++) {
            Node node = positions.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) node;
            String accountId = attr(el, "accountId");
            IbkrPosition position = new IbkrPosition(
                accountId,
                blankToNull(attr(el, "isin")),
                blankToNull(attr(el, "symbol")),
                blankToNull(attr(el, "description")),
                blankToNull(attr(el, "currency")),
                blankToNull(attr(el, "assetCategory")),
                blankToNull(attr(el, "levelOfDetail")),
                decimal(el, "position"),
                decimal(el, "markPrice"),
                decimal(el, "costBasisPrice"),
                decimal(el, "fxRateToBase")
            );
            byAccount.computeIfAbsent(accountId == null ? "" : accountId, k -> new ArrayList<>())
                .add(position);
        }

        List<IbkrAccountData> result = new ArrayList<>();
        for (Map.Entry<String, List<IbkrPosition>> entry : byAccount.entrySet()) {
            String accountId = entry.getKey();
            result.add(new IbkrAccountData(accountId, entry.getValue(), baseCurrencyByAccount.get(accountId),
                dateByAccount.get(accountId)));
        }
        log.info("IBKR Flex parsed {} open positions across {} account(s)",
            positions.getLength(), result.size());
        return result;
    }

    /**
     * The last day the statement covers: its {@code toDate}; only when that is absent, the day it was
     * generated ({@code whenGenerated="20261122;110616"}). Null when the one that applies is not a date.
     */
    private LocalDate statementDate(Element statement) {
        String rawToDate = blankToNull(attr(statement, "toDate"));
        if (rawToDate != null) {
            // Present but unreadable stays unknown: a recent generation date must not vouch for a report
            // whose covered period cannot be read (an old report re-generated today).
            return flexDate(rawToDate);
        }
        String generated = blankToNull(attr(statement, "whenGenerated"));
        return generated == null ? null : flexDate(generated.split("[;, T]", 2)[0]);
    }

    /** Flex lets the user pick the date format of a query; these two cover the usual choices. */
    private LocalDate flexDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (DateTimeFormatter format : List.of(DateTimeFormatter.BASIC_ISO_DATE, DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                return LocalDate.parse(raw.trim(), format);
            } catch (DateTimeParseException e) {
                // try the next one
            }
        }
        return null;
    }

    /**
     * Parses the optional {@code <AccountInformation accountId="..." currency="..." />}
     * element(s) into an accountId → base-currency map. This section only appears when
     * "Account Information" is enabled on the Flex Query; a statement without it (or
     * without a usable {@code accountId}/{@code currency} pair) yields an empty map, and
     * callers fall back to their pre-base-currency-detection behavior.
     */
    private Map<String, String> parseAccountInformation(Document doc) {
        Map<String, String> result = new HashMap<>();
        NodeList infos = doc.getElementsByTagName("AccountInformation");
        for (int i = 0; i < infos.getLength(); i++) {
            Node node = infos.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) node;
            String accountId = blankToNull(attr(el, "accountId"));
            String currency = blankToNull(attr(el, "currency"));
            if (accountId != null && currency != null) {
                result.put(accountId, currency);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------
    // HTTP + XML helpers
    // ---------------------------------------------------------------------------

    private String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "picsou-finance")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new SyncException("Interactive Brokers returned HTTP " + response.statusCode()
                    + ". Please check your token and try again.");
            }
            String body = response.body();
            // BodyHandlers.ofString() never yields null, but an empty 200 body would only
            // fail later in parse() with a misleading "non-XML response" — fail clearly here.
            if (body == null || body.isBlank()) {
                throw new SyncException("Interactive Brokers returned an empty response. Please try again.");
            }
            return body;
        } catch (SyncException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new SyncException("Unable to reach Interactive Brokers: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncException("IBKR request interrupted", e);
        }
    }

    /** The Flex Web Service answer, which the HTTP client already decoded as text. */
    private Document parse(String xml) {
        return parse(xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * Parses XML with external entities disabled (XXE-safe), since the payload is untrusted: it comes
     * from the network, or from a file the user uploads. Any DOCTYPE is refused outright, which closes
     * both external and internal entity expansion; the remaining switches are defence in depth.
     *
     * @param uploaded true for a file the user picked: its failure is then about the file, and none
     *                 of its content is logged
     */
    private Document parse(byte[] xml, boolean uploaded) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            try {
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            } catch (IllegalArgumentException e) {
                // A parser other than the JDK's may not know these; any DOCTYPE is refused above anyway.
            }
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml));
            return doc;
        } catch (ParserConfigurationException | SAXException | java.io.IOException e) {
            // Narrow on purpose: only genuine parse/IO failures become the user-facing
            // message — a programming error (NPE, class cast) must surface as itself.
            if (uploaded) {
                // A file the user picked can hold anything (account ids, positions, another
                // document altogether): only its size and the error type are logged.
                log.warn("Uploaded IBKR Flex file is not valid XML ({} bytes, {})",
                    xml.length, e.getClass().getSimpleName());
                throw new SyncException("The file is not a valid XML Flex report.", e);
            }
            // A non-XML body usually means an HTML error page or a network interception.
            // Deliberately truncated: a real statement carries the user's positions, and
            // full payloads do not belong in server logs — the length tells the rest.
            String text = new String(xml, StandardCharsets.UTF_8);
            log.error("Failed to parse IBKR Flex response as XML ({} chars, first 300 shown): {}",
                text.length(), text.substring(0, Math.min(300, text.length())));
            throw new SyncException("Interactive Brokers returned an unexpected (non-XML) response.", e);
        }
    }

    /** First direct/descendant child element text with the given tag, or null. */
    private String childText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return null;
        }
        String text = list.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private String attr(Element el, String name) {
        return el.hasAttribute(name) ? el.getAttribute(name).trim() : null;
    }

    /** Parses a numeric attribute; null/blank/unparseable → null (never throws). */
    private BigDecimal decimal(Element el, String name) {
        String raw = attr(el, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            log.warn("IBKR position: non-numeric {}='{}', treating as null", name, LogSanitizer.safe(raw));
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncException("IBKR polling interrupted", e);
        }
    }

    /**
     * A single open position as reported by IBKR. Amounts are in the security's
     * native {@code currency}; {@code fxRateToBase} converts that currency to the
     * account's IBKR base currency.
     *
     * @param accountId      IBKR account id (e.g. "U1234567")
     * @param isin           ISIN, may be null for instruments without one (some derivatives)
     * @param symbol         IBKR symbol (fallback ticker when there is no ISIN)
     * @param description    human-readable instrument name
     * @param currency       native quote currency (e.g. "USD", "EUR")
     * @param assetCategory  IBKR asset class ("STK", "ETF", "OPT", "FUT", "CASH", ...)
     * @param levelOfDetail  "SUMMARY" (aggregate) or "LOT" (per tax lot); used to de-dupe
     * @param position       signed quantity held
     * @param markPrice      current mark price per unit, native currency
     * @param costBasisPrice cost basis per unit, native currency (may be null)
     * @param fxRateToBase   native-currency → IBKR-base-currency rate (may be null)
     */
    public record IbkrPosition(
        String accountId,
        String isin,
        String symbol,
        String description,
        String currency,
        String assetCategory,
        String levelOfDetail,
        BigDecimal position,
        BigDecimal markPrice,
        BigDecimal costBasisPrice,
        BigDecimal fxRateToBase
    ) {}

    /**
     * @param accountId    IBKR account id
     * @param positions    open positions for this account
     * @param baseCurrency the account's IBKR base currency (e.g. "EUR", "USD"), from the
     *                     optional {@code AccountInformation} section of the Flex Query;
     *                     null when that section is not enabled on the query, or absent
     *                     from the statement
     * @param statementDate the last day the statement covers (its {@code toDate}); null when absent
     *                      or unreadable
     */
    public record IbkrAccountData(
        String accountId,
        List<IbkrPosition> positions,
        String baseCurrency,
        LocalDate statementDate
    ) {
        /** Convenience constructor for callers/tests that predate the statement date. */
        public IbkrAccountData(String accountId, List<IbkrPosition> positions, String baseCurrency) {
            this(accountId, positions, baseCurrency, null);
        }

        /** Convenience constructor for callers/tests that predate the base-currency field. */
        public IbkrAccountData(String accountId, List<IbkrPosition> positions) {
            this(accountId, positions, null, null);
        }
    }
}
