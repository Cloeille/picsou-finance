package com.picsou.adapter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import java.net.ConnectException;
import java.net.URI;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import com.picsou.support.WebClientStubs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.picsou.port.SidecarErrorCode.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The error contract every {@code {"detail": "<CODE>"}} sidecar connector shares, driven through
 * each real adapter's public methods. What differs between banks is only a row in {@link #RIGS}
 * or {@link #CODED}; bank-specific mapping (Caisse d'Epargne's statuses, CIC's 404 fallback, ...)
 * stays in the bank's own test.
 */
class SidecarContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration GENEROUS = Duration.ofSeconds(1);
    private static final Duration SHORT = Duration.ofMillis(20);
    private static final String STATE = "session-state-blob";
    private static final String UNCODED_401 = "{\"detail\":\"Authentication rejected\"}";

    enum Call { FETCH, INITIATE, COMPLETE }

    /** The three calls of one adapter; a null entry is a call the connector does not have. */
    record Calls(Runnable fetch, Runnable initiate, Runnable complete) {
        Runnable of(Call call) {
            return switch (call) {
                case FETCH -> fetch;
                case INITIATE -> initiate;
                case COMPLETE -> complete;
            };
        }
    }

    /**
     * @param emptyList     what an empty array means to the list fetch, or null when the fetch is not a list
     * @param completeRejects what an uncoded 401 means to the completion call, or null without one
     * @param fetchRejects  what an uncoded 401 means to the fetch, or null when it carries no meaning
     */
    record Rig(
        SidecarBank bank,
        BiFunction<WebClient, Duration, Calls> adapter,
        SidecarErrorCode emptyList,
        SidecarErrorCode completeRejects,
        SidecarErrorCode fetchRejects
    ) {
        Calls on(WebClient client, Duration timeout) {
            return adapter.apply(client, timeout);
        }
    }

    private static final Map<SidecarBank, Rig> RIGS = new EnumMap<>(SidecarBank.class);

    private static void rig(
        SidecarBank bank,
        BiFunction<WebClient, Duration, Calls> adapter,
        SidecarErrorCode emptyList,
        SidecarErrorCode completeRejects,
        SidecarErrorCode fetchRejects
    ) {
        RIGS.put(bank, new Rig(bank, adapter, emptyList, completeRejects, fetchRejects));
    }

    static {
        rig(SidecarBank.AMEX, (c, t) -> {
            var a = new AmexAdapter(c, JSON, t, t, t);
            return new Calls(() -> a.fetchAccounts(STATE), () -> a.initiateAuth("user", "secret", "sms"),
                () -> a.completeAuth("process", "123456"));
        }, INVALID_DATA, INVALID_OTP, null);
        rig(SidecarBank.AMUNDI, (c, t) -> {
            var a = new AmundiAdapter(c, JSON, t, t, t);
            return new Calls(() -> a.fetchPlans(STATE), () -> a.initiateAuth("login", "password"),
                () -> a.completeAuth("process", "123456"));
        }, PORTFOLIO_INCOMPLETE, INVALID_OTP, null);
        rig(SidecarBank.BOURSE_DIRECT, (c, t) -> {
            var a = new BourseDirectAdapter(c, JSON, t, t);
            return new Calls(() -> a.fetchAccounts(STATE), () -> a.initiateAuth("login", "password"),
                () -> a.completeAuth("process", "123456"));
        }, PORTFOLIO_INCOMPLETE, INVALID_OTP, null);
        rig(SidecarBank.BOURSO, (c, t) -> {
            var a = new BoursoAdapter(c, JSON, t, t, t);
            return new Calls(() -> a.fetchAccounts(STATE), () -> a.initiateAuth("12345678", "123456"),
                () -> a.completeAuth("process"));
        }, PORTFOLIO_INCOMPLETE, APP_VALIDATION_TIMEOUT, null);
        rig(SidecarBank.CAISSE_EPARGNE, (c, t) -> {
            var a = new CaisseEpargneAdapter(c, JSON, t, t, t, t, t);
            return new Calls(() -> a.fetchAccounts(STATE), () -> a.initiateAuth("12345678"),
                () -> a.completeAuth("process"));
        }, null, INVALID_CREDENTIALS, SESSION_EXPIRED);
        rig(SidecarBank.CIC, (c, t) -> {
            var a = new CicAdapter(c, JSON, t, t, t);
            return new Calls(() -> a.fetchAccounts(STATE), () -> a.initiateAuth("login", "password", null),
                () -> a.completeAuth("process", "123456"));
        }, PORTFOLIO_INCOMPLETE, APP_VALIDATION_TIMEOUT, null);
        rig(SidecarBank.CMES, (c, t) -> {
            var a = new CmesAdapter(c, JSON, t, t, t);
            return new Calls(() -> a.fetchPlans(STATE), () -> a.initiateAuth("login", "password", null),
                () -> a.completeAuth("process", "123456"));
        }, PORTFOLIO_INCOMPLETE, APP_VALIDATION_TIMEOUT, null);
        rig(SidecarBank.CORUM, (c, t) -> {
            var a = new CorumAdapter(c, JSON, t, t);
            return new Calls(() -> a.fetchSnapshot(STATE), () -> a.authenticate("000000", "password"), null);
        }, null, null, SESSION_EXPIRED);
        rig(SidecarBank.FORTUNEO, (c, t) -> {
            var a = new FortuneoAdapter(c, JSON, t, t);
            return new Calls(() -> a.fetchAccounts(STATE), () -> a.initiateAuth("login", "password"),
                () -> a.completeAuth("process", "123456"));
        }, PORTFOLIO_INCOMPLETE, INVALID_OTP, null);
        rig(SidecarBank.SOFIDY, (c, t) -> {
            var a = new SofidyAdapter(c, JSON, t, t, t);
            return new Calls(() -> a.fetchSnapshot(STATE), () -> a.initiateAuth("000000", "password"),
                () -> a.completeAuth("process", "123456"));
        }, null, MFA_INVALID, SESSION_EXPIRED);
    }

    /** A declared {@code detail} keeps its code whatever the status, and speaks in the bank's words. */
    record Coded(SidecarBank bank, Call call, HttpStatus status, SidecarErrorCode code, String wording) {
        @Override
        public String toString() {
            return bank + " " + call + " " + status.value() + " " + code;
        }
    }

    private static Coded coded(SidecarBank bank, Call call, HttpStatus status, SidecarErrorCode code) {
        return new Coded(bank, call, status, code, null);
    }

    private static final List<Coded> CODED = List.of(
        coded(SidecarBank.AMEX, Call.FETCH, HttpStatus.BAD_GATEWAY, UPSTREAM_FORMAT_CHANGED),
        coded(SidecarBank.AMEX, Call.COMPLETE, HttpStatus.UNAUTHORIZED, INVALID_OTP),
        coded(SidecarBank.AMUNDI, Call.FETCH, HttpStatus.BAD_GATEWAY, PORTFOLIO_INCOMPLETE),
        coded(SidecarBank.AMUNDI, Call.INITIATE, HttpStatus.FORBIDDEN, CAPTCHA_BLOCKED),
        coded(SidecarBank.AMUNDI, Call.COMPLETE, HttpStatus.REQUEST_TIMEOUT, APP_VALIDATION_TIMEOUT),
        coded(SidecarBank.BOURSE_DIRECT, Call.FETCH, HttpStatus.BAD_GATEWAY, PORTFOLIO_INCOMPLETE),
        coded(SidecarBank.BOURSE_DIRECT, Call.COMPLETE, HttpStatus.BAD_REQUEST, INVALID_OTP),
        coded(SidecarBank.BOURSO, Call.FETCH, HttpStatus.BAD_GATEWAY, PORTFOLIO_INCOMPLETE),
        coded(SidecarBank.BOURSO, Call.FETCH, HttpStatus.BAD_GATEWAY, UPSTREAM_FORMAT_CHANGED),
        new Coded(SidecarBank.BOURSO, Call.FETCH, HttpStatus.BAD_GATEWAY, IDENTITY_SELECTION_UNSUPPORTED,
            "several identities"),
        coded(SidecarBank.BOURSO, Call.FETCH, HttpStatus.UNAUTHORIZED, SESSION_EXPIRED),
        new Coded(SidecarBank.BOURSO, Call.INITIATE, HttpStatus.NOT_IMPLEMENTED, MFA_TYPE_UNSUPPORTED,
            "app validation"),
        new Coded(SidecarBank.BOURSO, Call.INITIATE, HttpStatus.UNAUTHORIZED, FRAUD_ACK_REQUIRED,
            "fraud-prevention notice"),
        coded(SidecarBank.BOURSO, Call.COMPLETE, HttpStatus.REQUEST_TIMEOUT, APP_VALIDATION_TIMEOUT),
        coded(SidecarBank.CAISSE_EPARGNE, Call.FETCH, HttpStatus.UNAUTHORIZED, SESSION_EXPIRED),
        coded(SidecarBank.CAISSE_EPARGNE, Call.FETCH, HttpStatus.BAD_GATEWAY, UPSTREAM_UNAVAILABLE),
        coded(SidecarBank.CAISSE_EPARGNE, Call.FETCH, HttpStatus.BAD_GATEWAY, UPSTREAM_FORMAT_CHANGED),
        coded(SidecarBank.CAISSE_EPARGNE, Call.FETCH, HttpStatus.BAD_REQUEST, INVALID_SESSION_STATE),
        coded(SidecarBank.CAISSE_EPARGNE, Call.FETCH, HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR),
        coded(SidecarBank.CIC, Call.FETCH, HttpStatus.UNAUTHORIZED, SESSION_EXPIRED),
        coded(SidecarBank.CIC, Call.FETCH, HttpStatus.BAD_GATEWAY, UPSTREAM_FORMAT_CHANGED),
        coded(SidecarBank.CIC, Call.COMPLETE, HttpStatus.BAD_REQUEST, INVALID_OTP),
        coded(SidecarBank.CIC, Call.COMPLETE, HttpStatus.UNAUTHORIZED, APP_VALIDATION_CANCELLED),
        coded(SidecarBank.CMES, Call.FETCH, HttpStatus.BAD_GATEWAY, UPSTREAM_FORMAT_CHANGED),
        coded(SidecarBank.CMES, Call.COMPLETE, HttpStatus.BAD_REQUEST, INVALID_OTP),
        coded(SidecarBank.CMES, Call.COMPLETE, HttpStatus.UNAUTHORIZED, APP_VALIDATION_CANCELLED),
        coded(SidecarBank.CORUM, Call.FETCH, HttpStatus.BAD_GATEWAY, PORTFOLIO_INCOMPLETE),
        coded(SidecarBank.CORUM, Call.FETCH, HttpStatus.CONFLICT, MULTIPLE_CONTRACTS),
        coded(SidecarBank.CORUM, Call.INITIATE, HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS),
        coded(SidecarBank.FORTUNEO, Call.FETCH, HttpStatus.BAD_GATEWAY, PORTFOLIO_INCOMPLETE),
        coded(SidecarBank.FORTUNEO, Call.FETCH, HttpStatus.CONFLICT, INVESTOR_PROFILE_REQUIRED),
        coded(SidecarBank.FORTUNEO, Call.COMPLETE, HttpStatus.BAD_REQUEST, INVALID_OTP),
        coded(SidecarBank.SOFIDY, Call.FETCH, HttpStatus.BAD_GATEWAY, PORTFOLIO_INCOMPLETE),
        coded(SidecarBank.SOFIDY, Call.INITIATE, HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS),
        coded(SidecarBank.SOFIDY, Call.COMPLETE, HttpStatus.UNAUTHORIZED, MFA_INVALID),
        coded(SidecarBank.SOFIDY, Call.COMPLETE, HttpStatus.GONE, AUTH_ATTEMPT_EXPIRED)
    );

    // -- the table of rows each scenario runs over ---------------------------------------------

    static Stream<Arguments> everyBank() {
        return RIGS.values().stream().map(rig -> Arguments.of(rig.bank(), rig));
    }

    static Stream<Arguments> everyCall() {
        return RIGS.values().stream().flatMap(rig -> java.util.Arrays.stream(Call.values())
            .filter(call -> rig.on(WebClientStubs.neverResponding(), GENEROUS).of(call) != null)
            .map(call -> Arguments.of(rig.bank(), call, rig)));
    }

    static Stream<Arguments> everyAuthCall() {
        return everyCall().filter(args -> args.get()[1] != Call.FETCH);
    }

    static Stream<Arguments> everyBankThatLists() {
        return RIGS.values().stream().filter(rig -> rig.emptyList() != null)
            .map(rig -> Arguments.of(rig.bank(), rig));
    }

    static Stream<Arguments> everyBankWithACompletion() {
        return RIGS.values().stream().filter(rig -> rig.completeRejects() != null)
            .map(rig -> Arguments.of(rig.bank(), rig));
    }

    static Stream<Arguments> everyBankWhoseFetchReadsA401AsAnExpiredSession() {
        return RIGS.values().stream().filter(rig -> rig.fetchRejects() != null)
            .map(rig -> Arguments.of(rig.bank(), rig));
    }

    // -- scenarios -----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBank")
    @DisplayName("an error body that is not JSON reads as the bank being unavailable")
    void malformedErrorJsonIsUnavailable(SidecarBank bank, Rig rig) {
        Calls calls = rig.on(WebClientStubs.returning(HttpStatus.BAD_GATEWAY, "not-json"), GENEROUS);

        assertFailsWith(calls.fetch(), UPSTREAM_UNAVAILABLE, bank.message(UPSTREAM_UNAVAILABLE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBank")
    @DisplayName("a detail code no enum constant carries reads as the bank being unavailable")
    void unknownCodeIsUnavailable(SidecarBank bank, Rig rig) {
        Calls calls = rig.on(
            WebClientStubs.returning(HttpStatus.BAD_GATEWAY, "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"), GENEROUS);

        assertFailsWith(calls.fetch(), UPSTREAM_UNAVAILABLE, bank.message(UPSTREAM_UNAVAILABLE));
    }

    /** Each session table's CHECK lists only its own bank's codes, so an undeclared one must never be stored. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBank")
    @DisplayName("a real code the bank never declared is treated as unknown")
    void undeclaredCodeIsUnavailable(SidecarBank bank, Rig rig) {
        SidecarErrorCode undeclared = java.util.Arrays.stream(SidecarErrorCode.values())
            .filter(code -> !bank.declares(code)).findFirst().orElseThrow();
        Calls calls = rig.on(
            WebClientStubs.returning(HttpStatus.BAD_GATEWAY, "{\"detail\":\"" + undeclared.name() + "\"}"), GENEROUS);

        assertFailsWith(calls.fetch(), UPSTREAM_UNAVAILABLE, bank.message(UPSTREAM_UNAVAILABLE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codedRows")
    @DisplayName("a declared detail code keeps its name and the bank's wording, whatever the status")
    void declaredCodeIsKept(Coded row) {
        Calls calls = RIGS.get(row.bank()).on(
            WebClientStubs.returning(row.status(), "{\"detail\":\"" + row.code().name() + "\"}"), GENEROUS);

        Runnable call = calls.of(row.call());
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SyncException.class, error -> {
            assertThat(error.getCode()).isEqualTo(row.code().name());
            assertThat(error.getMessage()).doesNotContain(STATE);
            if (row.wording() == null) {
                assertThat(error.getMessage()).isEqualTo(row.bank().message(row.code()));
            } else {
                assertThat(error.getMessage()).contains(row.wording());
            }
        });
    }

    static Stream<Coded> codedRows() {
        return CODED.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyAuthCall")
    @DisplayName("an empty success body on a login call reads as the bank being unavailable")
    void emptySuccessBodyIsUnavailable(SidecarBank bank, Call call, Rig rig) {
        Calls calls = rig.on(WebClientStubs.returning(HttpStatus.OK, ""), GENEROUS);

        assertFailsWith(calls.of(call), UPSTREAM_UNAVAILABLE, null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBank")
    @DisplayName("a bare 401 on the first call means the bank rejected the credentials")
    void uncoded401OnInitiateIsInvalidCredentials(SidecarBank bank, Rig rig) {
        Calls calls = rig.on(WebClientStubs.returning(HttpStatus.UNAUTHORIZED, UNCODED_401), GENEROUS);

        assertFailsWith(calls.initiate(), INVALID_CREDENTIALS, bank.message(INVALID_CREDENTIALS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBankWithACompletion")
    @DisplayName("a bare 401 on the completion means the second factor was rejected")
    void uncoded401OnCompleteIsTheSecondFactorFailure(SidecarBank bank, Rig rig) {
        Calls calls = rig.on(WebClientStubs.returning(HttpStatus.UNAUTHORIZED, UNCODED_401), GENEROUS);

        assertFailsWith(calls.complete(), rig.completeRejects(), bank.message(rig.completeRejects()));
    }

    /** A bare 401 can only mean the session died there: the credentials were checked at authentication. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBankWhoseFetchReadsA401AsAnExpiredSession")
    @DisplayName("a bare 401 on the fetch means the stored session died")
    void uncoded401OnFetchIsSessionExpired(SidecarBank bank, Rig rig) {
        Calls calls = rig.on(WebClientStubs.returning(HttpStatus.UNAUTHORIZED, UNCODED_401), GENEROUS);

        assertFailsWith(calls.fetch(), SESSION_EXPIRED, bank.message(SESSION_EXPIRED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBankWithACompletion")
    @DisplayName("a 410 on the completion means the login attempt expired, from the status alone")
    void goneOnCompleteIsAttemptExpired(SidecarBank bank, Rig rig) {
        Calls calls = rig.on(WebClientStubs.returning(HttpStatus.GONE, "{}"), GENEROUS);

        assertFailsWith(calls.complete(), AUTH_ATTEMPT_EXPIRED, bank.message(AUTH_ATTEMPT_EXPIRED));
    }

    /** An empty array is a failed scrape, never an empty portfolio to write over real holdings. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBankThatLists")
    @DisplayName("an empty array is a failed read")
    void emptyArrayIsAFailedRead(SidecarBank bank, Rig rig) {
        Calls calls = rig.on(WebClientStubs.returning(HttpStatus.OK, "[]"), GENEROUS);

        assertFailsWith(calls.fetch(), rig.emptyList(), null);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyCall")
    @DisplayName("a call that never answers is retryable and names the bank")
    void timeoutIsUnavailable(SidecarBank bank, Call call, Rig rig) {
        Calls calls = rig.on(WebClientStubs.neverResponding(), SHORT);

        assertFailsWith(calls.of(call), UPSTREAM_UNAVAILABLE, bank.label() + " took too long to respond. Please try again.");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyCall")
    @DisplayName("a sidecar that is not running is unavailable, not a wrong password")
    void unreachableSidecarIsUnavailable(SidecarBank bank, Call call, Rig rig) {
        Calls calls = rig.on(WebClientStubs.failing(new WebClientRequestException(
            new ConnectException("Connection refused"), HttpMethod.POST, URI.create("http://sidecar/"), new HttpHeaders())), SHORT);

        assertFailsWith(calls.of(call), UPSTREAM_UNAVAILABLE, null);
    }

    @ParameterizedTest
    @EnumSource(SidecarBank.class)
    @DisplayName("every bank is exercised by this suite")
    void everyBankHasARig(SidecarBank bank) {
        assertThat(RIGS).containsKey(bank);
    }

    /** @param message the exact wording, or null when it is the call's own and only the code is the contract */
    private static void assertFailsWith(Runnable call, SidecarErrorCode code, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SyncException.class, error -> {
            assertThat(error.getCode()).isEqualTo(code.name());
            if (message != null) {
                assertThat(error.getMessage()).isEqualTo(message);
            }
        });
    }
}
