package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import com.picsou.support.WebClientStubs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wire format and error mapping of the bnp-auth client; the shared contract lives in {@link SidecarContractTest}. */
class BnpAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration GENEROUS = Duration.ofSeconds(1);

    @Test
    void initiateSendsTheCustomerNumberAndBrandAndDecodesTheKeypad() {
        List<WebClientStubs.Call> calls = new ArrayList<>();
        BnpAdapter adapter = adapter(WebClientStubs.recording(calls, HttpStatus.OK, """
            {"processId": "p1", "expiresInSeconds": 300, "extra": true,
             "keypad": {"columns": 5, "keys": ["4", "7"]}}"""));

        BnpAdapter.InitiateResult result = adapter.initiateAuth("1234567890", "hellobank");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/initiate");
            assertThat(call.body()).contains("\"customerId\":\"1234567890\"").contains("\"brand\":\"hellobank\"");
            // The keypad flow never carries a password.
            assertThat(call.body()).doesNotContain("password");
        });
        assertThat(result.processId()).isEqualTo("p1");
        assertThat(result.keypad().columns()).isEqualTo(5);
        assertThat(result.keypad().keys()).containsExactly("4", "7");
    }

    @Test
    void theKeypadAckMustBeOneOfTheTwoKnownStatuses() {
        assertThat(adapter(WebClientStubs.returning("{\"processId\":\"p1\",\"status\":\"APPROVAL_PENDING\"}"))
            .submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6)).status()).isEqualTo("APPROVAL_PENDING");
        assertThat(adapter(WebClientStubs.returning("{\"processId\":\"p1\",\"status\":\"CODE_REQUIRED\"}"))
            .submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6)).status()).isEqualTo("CODE_REQUIRED");

        assertThatThrownBy(() -> adapter(WebClientStubs.returning("{\"processId\":\"p1\",\"status\":\"DONE\"}"))
            .submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6)))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED.name()));
    }

    @Test
    void theKeypadPositionsAreSentAsIntegers() {
        List<String> bodies = new ArrayList<>();
        BnpAdapter adapter = adapter(WebClientStubs.recordingBodies(bodies, HttpStatus.OK,
            "{\"processId\":\"p1\",\"status\":\"CODE_REQUIRED\"}"));

        adapter.submitKeypad("p1", List.of(3, 1, 4, 1, 5, 9));

        assertThat(bodies).singleElement().isEqualTo("{\"processId\":\"p1\",\"positions\":[3,1,4,1,5,9]}");
    }

    @Test
    void completeReturnsOnlyTheSessionState() {
        List<String> bodies = new ArrayList<>();
        BnpAdapter adapter = adapter(WebClientStubs.recordingBodies(bodies, HttpStatus.OK,
            "{\"sessionState\":\"jar\",\"ignored\":1}"));

        assertThat(adapter.completeAuth("p1", "123456")).isEqualTo("jar");
        assertThat(adapter.completeAuth("p1", null)).isEqualTo("jar");

        assertThat(bodies).containsExactly(
            "{\"processId\":\"p1\",\"code\":\"123456\"}", "{\"processId\":\"p1\",\"code\":null}");
    }

    @Test
    void loginStatusesMapToTheirOwnCodes() {
        // /initiate has no approval step: a timeout there is the bank being unreachable.
        assertCode(HttpStatus.REQUEST_TIMEOUT, "{\"detail\":\"x\"}", SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        assertThatThrownBy(() -> adapter(WebClientStubs.returning(HttpStatus.REQUEST_TIMEOUT, "{\"detail\":\"x\"}"))
            .completeAuth("p1", null))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.APP_VALIDATION_TIMEOUT.name()));
        assertCode(HttpStatus.CONFLICT, "{\"detail\":\"x\"}", SidecarErrorCode.KEYPAD_CHANGED);
        assertCode(HttpStatus.TOO_MANY_REQUESTS, "{\"detail\":\"x\"}", SidecarErrorCode.RATE_LIMITED);
        assertCode(HttpStatus.UNAUTHORIZED, "{\"detail\":\"INVALID_CREDENTIALS\"}", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void aConflictCarriesItsDeclaredCodeAndOnlyFallsBackToKeypadChangedWithoutOne() {
        // The sidecar answers 409 for three different reasons; the detail tells them apart.
        assertCode(HttpStatus.CONFLICT, "{\"detail\":\"ACTION_REQUIRED\"}", SidecarErrorCode.ACTION_REQUIRED);
        assertCode(HttpStatus.CONFLICT, "{\"detail\":\"MFA_TYPE_UNSUPPORTED\"}", SidecarErrorCode.MFA_TYPE_UNSUPPORTED);
        assertCode(HttpStatus.CONFLICT, "{\"detail\":\"KEYPAD_CHANGED\"}", SidecarErrorCode.KEYPAD_CHANGED);
        // No detail, a blank one, an undeclared one, or a body that is not JSON: the page changed.
        assertCode(HttpStatus.CONFLICT, "{}", SidecarErrorCode.KEYPAD_CHANGED);
        assertCode(HttpStatus.CONFLICT, "{\"detail\":\" \"}", SidecarErrorCode.KEYPAD_CHANGED);
        assertCode(HttpStatus.CONFLICT, "{\"detail\":\"NOT_A_CODE\"}", SidecarErrorCode.KEYPAD_CHANGED);
        assertCode(HttpStatus.CONFLICT, "not json", SidecarErrorCode.KEYPAD_CHANGED);
    }

    @Test
    void aWrongSmsCodeIsInvalidOtpWhileAnAppApprovalFailureIsNot() {
        assertThatThrownBy(() -> adapter(WebClientStubs.returning(HttpStatus.UNAUTHORIZED, "{\"detail\":\"INVALID_OTP\"}"))
            .completeAuth("p1", "000000"))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.INVALID_OTP.name()));
    }

    @Test
    void anUndecodableLoginAnswerIsAnUnavailableUpstream() {
        assertThatThrownBy(() -> adapter(WebClientStubs.returning("[1,2,3]")).initiateAuth("1234567890", "bnp"))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));
    }

    @Test
    void aSilentSidecarIsCutOffByTheStepTimeout() {
        BnpAdapter adapter = new BnpAdapter(WebClientStubs.neverResponding(), JSON,
            GENEROUS, Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(20));

        assertThatThrownBy(() -> adapter.initiateAuth("1234567890", "bnp")).isInstanceOf(SyncException.class);
    }

    @Test
    void accountsDecodeEveryProductWithItsKindAndTheUnsupportedList() {
        BnpAdapter adapter = adapter(WebClientStubs.returning("""
            {"brand": "bnp", "sessionState": "rotated",
             "unsupported": [{"externalId": "t1", "familyCode": "3"}],
             "accounts": [{"externalId": "c1", "kind": "CURRENT_ACCOUNT", "name": "Compte", "balance": "12.30",
               "cashBalance": null, "currency": "EUR", "iban": null, "snapshotComplete": true,
               "transactions": [{"externalId": "op1", "date": "2026-01-05", "description": "CARTE SYNTHETIQUE",
                                 "amount": "-12.30"}],
               "positions": []},
              {"externalId": "pea1", "kind": "PEA", "name": "PEA", "balance": "1500.00", "cashBalance": "500.00",
               "currency": "EUR", "snapshotComplete": true,
               "positions": [{"isin": "FR0000000001", "label": "Action A", "quantity": "10", "value": "1000.00",
                              "pnl": "100.00"}]}]}
            """));

        BnpAdapter.AccountsSnapshot snapshot = adapter.fetchAccounts("state");

        assertThat(snapshot.sessionState()).isEqualTo("rotated");
        assertThat(snapshot.unsupported()).containsExactly(new BnpAdapter.Unsupported("t1", "3"));
        assertThat(snapshot.accounts()).extracting(BnpAdapter.AccountData::kind).containsExactly("CURRENT_ACCOUNT", "PEA");
        assertThat(snapshot.accounts().getFirst().transactions()).singleElement()
            .satisfies(tx -> assertThat(tx.amount()).isEqualByComparingTo("-12.30"));
        assertThat(snapshot.accounts().get(1).positions().getFirst().pnl()).isEqualByComparingTo("100.00");
        assertThat(snapshot.accounts().get(1).cashBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void aRefusedSessionIsSessionExpired() {
        assertThatThrownBy(() -> adapter(WebClientStubs.returning(HttpStatus.UNAUTHORIZED, "{\"detail\":\"SESSION_EXPIRED\"}"))
            .fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED.name()));
    }

    @Test
    void theSessionStateAndLoginInputsNeverAppearInTheLoggableToStrings() throws Exception {
        for (Class<?> type : BnpAdapter.class.getDeclaredClasses()) {
            if (!type.isRecord() || java.lang.reflect.Modifier.isPublic(type.getModifiers())) continue;
            var ctor = type.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                args[i] = types[i] == String.class ? "SECRET-VALUE" : types[i] == List.class ? List.of(1, 2) : null;
            }
            String text = ctor.newInstance(args).toString();
            if (type.getSimpleName().equals("KeypadBody") || type.getSimpleName().equals("CompleteBody")
                || type.getSimpleName().equals("SessionBody") || type.getSimpleName().equals("InitiateBody")) {
                assertThat(text).as(type.getSimpleName()).doesNotContain("1, 2").doesNotContain("[1,");
            }
            if (type.getSimpleName().equals("SessionBody")) {
                assertThat(text).doesNotContain("SECRET-VALUE");
            }
        }
    }

    private static void assertCode(HttpStatus status, String body, SidecarErrorCode expected) {
        assertThatThrownBy(() -> adapter(WebClientStubs.returning(status, body)).initiateAuth("1234567890", "bnp"))
            .isInstanceOfSatisfying(SyncException.class, e -> assertThat(e.getCode()).isEqualTo(expected.name()));
    }

    private static BnpAdapter adapter(WebClient client) {
        return new BnpAdapter(client, JSON);
    }
}
