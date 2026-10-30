package com.picsou.service;

import com.picsou.dto.ReceivedDividendResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.DividendEvent;
import com.picsou.model.DividendOrigin;
import com.picsou.model.DividendSource;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DividendSourceRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Recognises a cash credit as a dividend by matching it against the stored dividend history,
 * and lists the dividends already received. Never calls Yahoo or Nasdaq: it reads the same
 * {@code dividend_source} tables as {@link DividendForecastService}.
 *
 * <p>An amount alone is never enough. A 100 € deposit into a CTO can fit a dividend band by
 * coincidence, so a credit is classified only with label evidence (see {@link #classifyRecent}).
 */
@Service
public class DividendReceiptService {

    static final int LOOKBACK_DAYS = 45;
    /** Same window as {@code DividendForecastService.PAYMENT_LAG_WINDOW_DAYS}. */
    static final int EX_TO_PAYMENT_MAX_DAYS = 60;
    static final int PAYMENT_DATE_TOLERANCE_DAYS = 7;
    /** Received / expected gross. The band absorbs withholding tax and FX drift. */
    static final BigDecimal MIN_RATIO = new BigDecimal("0.40");
    static final BigDecimal MAX_RATIO = new BigDecimal("1.10");
    static final String CATEGORY_SLUG = "revenus-capital";
    /** Raw Trade Republic category of a BUY or SELL row, kept even when no Picsou category resolved. */
    private static final String TRADING = "TRADING";
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 366;

    private static final int RATIO_SCALE = 8;
    private static final int MIN_ROOT_LENGTH = 3;
    private static final int MIN_STRONG_ROOT_LENGTH = 4;
    private static final int MIN_NAME_TOKEN_LENGTH = 4;
    private static final List<String> KEYWORDS = List.of("divid", "coupon", "dvd", "distribution");
    // Words shared by many issuers or fund families: matching one would name no instrument.
    private static final Set<String> NAME_STOP_WORDS = Set.of(
        "group", "groupe", "holding", "holdings", "corp", "corporation", "company", "inc",
        "ishares", "amundi", "lyxor", "xtrackers", "vanguard", "ucits", "core", "msci", "world",
        "index", "class", "shares", "action", "actions", "etf");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

    private static final Comparator<ReceivedDividendResponse> NEWEST_FIRST =
        Comparator.comparing(ReceivedDividendResponse::receivedDate, Comparator.reverseOrder())
            .thenComparing(ReceivedDividendResponse::transactionId,
                Comparator.nullsLast(Comparator.reverseOrder()));

    private final AccountRepository accounts;
    private final AccountAccessResolver access;
    private final AccountHoldingRepository holdings;
    private final DividendSourceRepository sources;
    private final TransactionRepository transactions;
    private final CategorizationService categorizationService;

    public DividendReceiptService(
        AccountRepository accounts,
        AccountAccessResolver access,
        AccountHoldingRepository holdings,
        DividendSourceRepository sources,
        TransactionRepository transactions,
        CategorizationService categorizationService
    ) {
        this.accounts = accounts;
        this.access = access;
        this.holdings = holdings;
        this.sources = sources;
        this.transactions = transactions;
        this.categorizationService = categorizationService;
    }

    /** Days are clamped to {@code [1, 366]} so a caller cannot ask for the whole ledger. */
    public static int clampDays(int days) {
        return Math.clamp(days, MIN_DAYS, MAX_DAYS);
    }

    /**
     * Marks the recent cash credits that match a stored dividend event. Returns the number of
     * transactions changed; a second run changes nothing.
     *
     * <p>Owned accounts only: categories are member-scoped, so a co-owned row is classified by
     * its owner, never with another member's category.
     */
    @Transactional
    public int classifyRecent(Long memberId, LocalDate today) {
        List<Account> owned = accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId);
        if (owned.isEmpty()) {
            return 0;
        }
        List<Long> ownedIds = owned.stream().map(Account::getId).filter(Objects::nonNull).toList();
        List<Transaction> credits = transactions
            .findByAccountIdInAndDateBetweenOrderByDateAsc(ownedIds, today.minusDays(LOOKBACK_DAYS), today)
            .stream()
            .filter(DividendReceiptService::isClassifiable)
            .toList();
        if (credits.isEmpty()) {
            return 0;
        }
        List<Line> lines = holdings.findPositiveByAccountIdIn(ownedIds).stream()
            .filter(h -> h.getQuantity() != null && h.getQuantity().signum() > 0)
            .filter(h -> h.getTicker() != null && !h.getTicker().isBlank())
            .map(h -> new Line(accountOf(h, owned), h))
            .filter(line -> line.account() != null && line.account().getType() != AccountType.CRYPTO)
            .toList();
        if (lines.isEmpty()) {
            return 0;
        }
        Map<String, DividendSource> byTicker = sourcesByTicker(lines.stream()
            .map(line -> line.holding().getTicker()).toList());
        if (byTicker.isEmpty()) {
            return 0;
        }
        Category target = null;
        boolean targetLoaded = false;
        List<Transaction> changed = new ArrayList<>();
        for (Transaction credit : credits) {
            Line picked = pick(credit, candidateLines(credit, lines), byTicker);
            if (picked == null) {
                continue;
            }
            if (!targetLoaded) {
                target = categorizationService.categoriesBySlug(memberId).get(CATEGORY_SLUG);
                targetLoaded = true;
            }
            if (apply(credit, picked.holding(), target)) {
                changed.add(credit);
            }
        }
        if (!changed.isEmpty()) {
            transactions.saveAll(changed);
        }
        return changed.size();
    }

    /** Dividends received over the last {@code days} days on every account the member can read. */
    @Transactional(readOnly = true)
    public List<ReceivedDividendResponse> received(Long memberId, LocalDate today, int days) {
        List<Account> readable = access.readableAccounts(memberId);
        if (readable.isEmpty()) {
            return List.of();
        }
        Map<Long, Account> byId = readable.stream()
            .filter(a -> a.getId() != null)
            .collect(Collectors.toMap(Account::getId, a -> a, (a, b) -> a));
        List<Transaction> rows = transactions
            .findByAccountIdInAndDateBetweenOrderByDateAsc(
                List.copyOf(byId.keySet()), today.minusDays(clampDays(days)), today)
            .stream()
            .filter(tx -> tx.getTxType() == TransactionType.DIVIDEND)
            .filter(tx -> tx.getTicker() != null && !tx.getTicker().isBlank())
            .filter(tx -> tx.getAmount() != null && tx.getAmount().signum() > 0)
            .toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, DividendSource> byTicker = sourcesByTicker(rows.stream().map(Transaction::getTicker).toList());
        List<Line> lines = holdings.findPositiveByAccountIdIn(byId.keySet()).stream()
            .filter(h -> h.getAccount() != null && h.getTicker() != null)
            .map(h -> new Line(byId.get(h.getAccount().getId()), h))
            .filter(line -> line.account() != null)
            .toList();
        List<ReceivedDividendResponse> out = new ArrayList<>();
        for (Transaction tx : rows) {
            Account credited = tx.getAccount() == null ? null : byId.get(tx.getAccount().getId());
            if (credited == null) {
                continue;
            }
            Account account = holdingAccount(credited, tx.getTicker(), lines);
            DividendSource source = byTicker.get(tx.getTicker().toUpperCase(Locale.ROOT));
            DividendEvent event = source == null ? null : eventFor(source, tx.getDate());
            LocalDate exDate = event == null ? null : event.getExDate();
            String id = exDate != null
                ? DividendForecastService.id(DividendOrigin.HOLDING, account.getId(), tx.getTicker(), exDate)
                : "HOLDING:" + account.getId() + ":" + tx.getTicker() + ":tx" + tx.getId();
            out.add(new ReceivedDividendResponse(
                id,
                tx.getId(),
                account.getId(),
                account.getName(),
                account.getType(),
                tx.getTicker(),
                tx.getName(),
                tx.getDate(),
                tx.getAmount(),
                tx.getNativeCurrency(),
                exDate,
                event == null ? null : perShare(tx, event, source),
                DividendForecastService.countsInBudget(account.getType(), false)));
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    /**
     * The account that holds the line: the credited one when it holds the ticker, else a readable
     * account of the same provider that does (Trade Republic credits "TR Cash" for a PEA line).
     * The forecast id and the budget flag come from that account.
     */
    private static Account holdingAccount(Account credited, String ticker, List<Line> lines) {
        List<Account> holders = lines.stream()
            .filter(line -> line.holding().getTicker().equalsIgnoreCase(ticker))
            .map(Line::account)
            .toList();
        if (holders.stream().anyMatch(a -> Objects.equals(a.getId(), credited.getId()))
            || credited.getProvider() == null) {
            return credited;
        }
        List<Account> sameProvider = holders.stream()
            .filter(a -> credited.getProvider().equals(a.getProvider()))
            .distinct()
            .toList();
        return sameProvider.size() == 1 ? sameProvider.getFirst() : credited;
    }

    private static boolean isClassifiable(Transaction tx) {
        // A TRANSFER or EXPENSE auto category marks a trade leg (Trade Republic books a BUY
        // investment leg and a SELL cash leg as positive DEPOSITs): never a dividend.
        // A hand-entered row saved with a category before categoryManual was set on it carries
        // the user's choice all the same.
        return tx.getAmount() != null && tx.getAmount().signum() > 0
            && !tx.isCategoryManual()
            && !(tx.isManual() && tx.getCategoryRef() != null)
            && replaceable(tx.getCategoryRef())
            && !TRADING.equalsIgnoreCase(tx.getCategory())
            && (tx.getTxType() == null || tx.getTxType() == TransactionType.DEPOSIT
                || tx.getTxType() == TransactionType.DIVIDEND);
    }

    private Map<String, DividendSource> sourcesByTicker(Collection<String> tickers) {
        Set<String> upper = tickers.stream()
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        // Any status: a FAILED refresh keeps the last good events (see DividendHistoryService).
        return sources.findAllWithEventsByTickerIn(upper).stream()
            .collect(Collectors.toMap(s -> s.getTicker().toUpperCase(Locale.ROOT), s -> s, (a, b) -> a));
    }

    private static Account accountOf(AccountHolding holding, List<Account> owned) {
        if (holding.getAccount() == null) {
            return null;
        }
        Long id = holding.getAccount().getId();
        return owned.stream().filter(a -> Objects.equals(a.getId(), id)).findFirst().orElse(null);
    }

    /**
     * Lines of the credited account. Trade Republic pays dividends into the cash account while
     * the lines sit on the PEA or securities account, so with no line of its own a credit
     * falls back to the owned accounts of the same provider.
     */
    private static List<Line> candidateLines(Transaction credit, List<Line> lines) {
        Account account = credit.getAccount();
        if (account == null) {
            return List.of();
        }
        List<Line> own = lines.stream()
            .filter(line -> Objects.equals(line.account().getId(), account.getId()))
            .toList();
        if (!own.isEmpty() || account.getProvider() == null) {
            return own;
        }
        return lines.stream()
            .filter(line -> account.getProvider().equals(line.account().getProvider()))
            .toList();
    }

    private Line pick(Transaction credit, List<Line> lines, Map<String, DividendSource> byTicker) {
        Set<String> labelWords = wordsOf(labelOf(credit));
        // Word prefixes, not substrings: "divid" must not match "individuel".
        boolean keyword = labelWords.stream()
            .anyMatch(word -> KEYWORDS.stream().anyMatch(word::startsWith));
        boolean declared = credit.getTxType() == TransactionType.DIVIDEND;
        List<Line> candidates = new ArrayList<>();
        for (Line line : lines) {
            String ticker = line.holding().getTicker();
            if (credit.getTicker() != null && !credit.getTicker().isBlank()
                && !credit.getTicker().equalsIgnoreCase(ticker)) {
                continue;
            }
            DividendSource source = byTicker.get(ticker.toUpperCase(Locale.ROOT));
            if (source != null && fits(credit, line.holding(), source)) {
                candidates.add(line);
            }
        }
        List<Line> named = candidates.stream()
            .filter(line -> namesInstrument(labelWords, line.holding(), keyword || declared))
            .toList();
        if (named.size() == 1) {
            return named.getFirst();
        }
        if ((keyword || declared) && candidates.size() == 1) {
            return candidates.getFirst();
        }
        return null;
    }

    /** Date window, payment date, currency and amount band. Evidence is checked by the caller. */
    private static boolean fits(Transaction credit, AccountHolding holding, DividendSource source) {
        DividendEvent event = eventFor(source, credit.getDate());
        if (event == null) {
            return false;
        }
        if (event.getPaymentDate() != null
            && Math.abs(ChronoUnit.DAYS.between(credit.getDate(), event.getPaymentDate()))
                > PAYMENT_DATE_TOLERANCE_DAYS) {
            return false;
        }
        BigDecimal perShare = perShare(credit, event, source);
        if (perShare == null) {
            return false;
        }
        BigDecimal expected = holding.getQuantity().multiply(perShare);
        if (expected.signum() <= 0) {
            return false;
        }
        BigDecimal ratio = credit.getAmount().divide(expected, RATIO_SCALE, RoundingMode.HALF_UP);
        return ratio.compareTo(MIN_RATIO) >= 0 && ratio.compareTo(MAX_RATIO) <= 0;
    }

    /** Per-share amount in the credit's currency, or null when no stored amount is in it. */
    private static BigDecimal perShare(Transaction credit, DividendEvent event, DividendSource source) {
        String currency = credit.getNativeCurrency();
        if (currency == null || "EUR".equalsIgnoreCase(currency)) {
            return event.getAmountEur();
        }
        String sourceCurrency = source.getCurrency();
        if (sourceCurrency == null || event.getAmount() == null) {
            return null;
        }
        if (!poundsCode(currency).equalsIgnoreCase(poundsCode(sourceCurrency))) {
            return null;
        }
        return event.getAmount().movePointRight(pencePlaces(currency) - pencePlaces(sourceCurrency));
    }

    /**
     * Yahoo quotes London lines in pence as the case-sensitive {@code GBp}; {@code GBX} is the
     * other spelling. Same rule as {@code YahooFinancePriceProvider}.
     */
    private static boolean isPence(String currency) {
        return "GBp".equals(currency) || "GBX".equalsIgnoreCase(currency);
    }

    private static String poundsCode(String currency) {
        return isPence(currency) ? "GBP" : currency;
    }

    private static int pencePlaces(String currency) {
        return isPence(currency) ? 2 : 0;
    }

    /** Latest ex-date in {@code [date - 60 days, date]}: an older one is a different payment. */
    private static DividendEvent eventFor(DividendSource source, LocalDate date) {
        if (source.getEvents() == null || date == null) {
            return null;
        }
        LocalDate earliest = date.minusDays(EX_TO_PAYMENT_MAX_DAYS);
        return source.getEvents().stream()
            .filter(e -> e.getExDate() != null && !e.getExDate().isBefore(earliest) && !e.getExDate().isAfter(date))
            .max(Comparator.comparing(DividendEvent::getExDate))
            .orElse(null);
    }

    /**
     * A ticker root of {@value #MIN_ROOT_LENGTH} letters ({@code AIR}, {@code VIE}, {@code SAN})
     * is a common word, so it names the instrument only next to other dividend evidence.
     */
    private static boolean namesInstrument(Set<String> labelWords, AccountHolding holding, boolean evidence) {
        String ticker = holding.getTicker().toLowerCase(Locale.ROOT);
        int dot = ticker.indexOf('.');
        String root = dot < 0 ? ticker : ticker.substring(0, dot);
        int minRoot = evidence ? MIN_ROOT_LENGTH : MIN_STRONG_ROOT_LENGTH;
        if (root.length() >= minRoot && labelWords.contains(root)) {
            return true;
        }
        if (holding.getName() == null) {
            return false;
        }
        return wordsOf(normalize(holding.getName())).stream()
            .anyMatch(word -> word.length() >= MIN_NAME_TOKEN_LENGTH
                && !NAME_STOP_WORDS.contains(word)
                && labelWords.contains(word));
    }

    private static String labelOf(Transaction tx) {
        return normalize(String.join(" ",
            nullToEmpty(tx.getDescription()), nullToEmpty(tx.getCounterparty()), nullToEmpty(tx.getMerchantLabel())));
    }

    private static String normalize(String text) {
        String stripped = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT);
    }

    private static Set<String> wordsOf(String normalized) {
        Set<String> words = new HashSet<>();
        for (String word : NON_WORD.split(normalized)) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Writes only what differs, so an unchanged row is not counted and not saved again. */
    private static boolean apply(Transaction tx, AccountHolding holding, Category target) {
        boolean changed = false;
        if (tx.getTxType() != TransactionType.DIVIDEND) {
            tx.setTxType(TransactionType.DIVIDEND);
            changed = true;
        }
        if (tx.getTicker() == null || tx.getTicker().isBlank()) {
            tx.setTicker(holding.getTicker());
            changed = true;
        }
        if ((tx.getName() == null || tx.getName().isBlank())
            && holding.getName() != null && !holding.getName().isBlank()) {
            tx.setName(holding.getName());
            changed = true;
        }
        if (target != null && replaceable(tx.getCategoryRef()) && !sameCategory(tx.getCategoryRef(), target)) {
            tx.setCategoryRef(target);
            changed = true;
        }
        return changed;
    }

    /** Fills an empty category or an automatic INCOME one. A TRANSFER or EXPENSE auto category stays. */
    private static boolean replaceable(Category current) {
        return current == null || current.getKind() == CategoryKind.INCOME;
    }

    private static boolean sameCategory(Category current, Category target) {
        if (current == null) {
            return false;
        }
        return current == target || (current.getId() != null && current.getId().equals(target.getId()));
    }

    private record Line(Account account, AccountHolding holding) {}

    /**
     * What a recognised dividend row carries beyond the provider's fields. A provider import
     * that rewrites the row must restore it: {@link #classifyRecent} only revisits the last
     * {@value #LOOKBACK_DAYS} days, and the holding it matched may be gone by then.
     */
    record Recognition(String ticker, String name, Category category, boolean categoryManual) {

        /** The recognition a stored row carries, or null when it is not a dividend. */
        static Recognition of(Transaction tx) {
            if (tx.getTxType() != TransactionType.DIVIDEND || tx.getTicker() == null || tx.getTicker().isBlank()) {
                return null;
            }
            return new Recognition(tx.getTicker(), tx.getName(), tx.getCategoryRef(), tx.isCategoryManual());
        }

        /** Restores it on the provider's version of the row, unless the provider types or names it otherwise. */
        void restoreOn(Transaction tx) {
            boolean providerTyped = tx.getTxType() != null && tx.getTxType() != TransactionType.DEPOSIT
                && tx.getTxType() != TransactionType.DIVIDEND;
            boolean providerNamed = tx.getTicker() != null && !tx.getTicker().isBlank()
                && !tx.getTicker().equalsIgnoreCase(ticker);
            if (providerTyped || providerNamed) {
                return;
            }
            tx.setTxType(TransactionType.DIVIDEND);
            if (tx.getTicker() == null || tx.getTicker().isBlank()) {
                tx.setTicker(ticker);
            }
            if (tx.getName() == null || tx.getName().isBlank()) {
                tx.setName(name);
            }
            if (tx.getCategoryRef() == null) {
                tx.setCategoryRef(category);
                tx.setCategoryManual(categoryManual);
            }
        }
    }
}
