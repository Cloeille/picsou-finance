package com.picsou.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.TimeZone;

/**
 * Pins the JVM default time zone to {@code app.timezone} (default {@code Europe/Paris}).
 *
 * <p>Nothing used to pin it, so the app ran in whatever zone the host or base image happened to
 * expose — UTC for the published image. That is not a cosmetic difference: the domain reads the
 * default zone in ~40 places and two of them are user-visible bugs.
 *
 * <ul>
 *   <li><b>The 24H chart was two hours stale on its stock points.</b>
 *       {@code HistoryService.buildIntradayHistory} walks hourly timestamps from
 *       {@code LocalDateTime.now()} and looks each one up with {@code floorEntry}, while
 *       {@link com.picsou.adapter.YahooFinancePriceProvider} keyed its intraday prices in
 *       Europe/Paris. In a UTC container {@code ts = 14:00} (UTC) matched the Yahoo entry keyed
 *       {@code 14:00} Paris — the 12:00 UTC quote. Crypto points on the same chart were aligned,
 *       because CoinGecko keyed in UTC, so the two halves of one line disagreed.</li>
 *   <li><b>A sync between 00:00 and 02:00 Paris wrote onto the previous day.</b> Every connector
 *       ends with {@code upsertSnapshot(account, value, LocalDate.now())}. At 00:30 CEST that is
 *       22:30 UTC the day before, and the upsert keys on (account, date) — so reconnecting a
 *       broker after midnight overwrote yesterday's closing snapshot with tonight's value.</li>
 * </ul>
 *
 * <p>One zone for {@code now()} and for both price adapters removes both. This class is that one
 * zone: every {@code LocalDate.now()} / {@code LocalDateTime.now()} / {@code ZoneId.systemDefault()}
 * in the codebase follows it without being touched, which is what makes a single property a
 * complete fix rather than a 40-site refactor.
 *
 * <p><b>Why an environment listener and not {@code @PostConstruct}.</b> Bean initialisation order
 * is not specified, and some beans read the default zone as they are built — Hibernate resolves
 * the JDBC time zone when the {@code SessionFactory} is created. Reacting to
 * {@link ApplicationEnvironmentPreparedEvent} runs after the {@code Environment} is populated (so
 * {@code application.yml} is readable) but before the context exists, so no bean can observe the
 * unpinned zone. {@link Ordered#LOWEST_PRECEDENCE} is what puts this listener after Spring Boot's
 * own config-data processing, which is registered on the same event at
 * {@code HIGHEST_PRECEDENCE + 10}; without it the property would not be loaded yet and the
 * default would silently win.
 *
 * <p>Registered explicitly in {@link com.picsou.PicsouApplication#main}, not by component scan: at
 * this point in the lifecycle there is no context to scan.
 */
public class TimeZoneConfig implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TimeZoneConfig.class);

    /** Single source of truth for the application's civil time zone. */
    public static final String PROPERTY = "app.timezone";

    /**
     * Default zone. Europe/Paris rather than UTC because the product is a French personal-finance
     * dashboard: "today" in a snapshot, a goal deadline or a chart axis means the user's civil day,
     * and Yahoo's intraday keys were already written against this zone on purpose.
     */
    public static final String DEFAULT_ZONE = "Europe/Paris";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        pin(resolve(event.getEnvironment()));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Reads {@link #PROPERTY} from the environment, falling back to {@link #DEFAULT_ZONE}. */
    static String resolve(Environment environment) {
        String configured = environment.getProperty(PROPERTY);
        return configured == null || configured.isBlank() ? DEFAULT_ZONE : configured;
    }

    /**
     * Applies {@code configured} as the JVM default zone.
     *
     * <p>{@code user.timezone} is set alongside {@link TimeZone#setDefault} because the two are
     * read by different things: library code that calls {@code TimeZone.getDefault()} sees the
     * former, while anything re-deriving the zone from the system property (and the JVM's own
     * startup reporting) sees the latter. Leaving them disagreeing is how a zone fix half-applies.
     *
     * @return the zone that is now the JVM default
     * @throws IllegalStateException if {@code configured} is not a valid zone ID
     */
    static ZoneId pin(String configured) {
        ZoneId zone = parse(configured);
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        System.setProperty("user.timezone", zone.getId());
        log.info("Application time zone pinned to {} (from {})", zone.getId(), PROPERTY);
        return zone;
    }

    /**
     * Parses a zone ID, failing startup on a bad one.
     *
     * <p>Deliberately not a silent fallback to {@link #DEFAULT_ZONE}: an operator who set
     * {@code APP_TIMEZONE} to something the JVM does not recognise wants to know now, not to
     * discover months later that every snapshot has been dated in the wrong zone. This can only be
     * reached when the value was set explicitly, so it cannot be tripped by a stray host setting.
     */
    static ZoneId parse(String configured) {
        try {
            return ZoneId.of(configured.trim());
        } catch (DateTimeException ex) {
            throw new IllegalStateException(
                "Invalid " + PROPERTY + ": '" + configured + "'. Expected an IANA zone ID such as "
                    + DEFAULT_ZONE + " or UTC.", ex);
        }
    }
}
