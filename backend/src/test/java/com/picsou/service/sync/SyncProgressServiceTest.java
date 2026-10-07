package com.picsou.service.sync;

import com.picsou.dto.DiscoveredRevolutAccount;
import com.picsou.dto.SyncProgress;
import com.picsou.model.AccountType;
import com.picsou.port.RevolutPort.RevolutAccountData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit tests for {@link SyncProgressService} -- a POJO in-memory registry (no Spring context,
 * no mocks needed). Lives in the same package to reach the package-private {@code jobs} map only
 * indirectly through the public API, mirroring how {@code RevolutSyncService} and
 * {@code RevolutController} actually use it.
 */
class SyncProgressServiceTest {

    private static final Long MEMBER_ID = 42L;

    private static RevolutAccountData rawAccount(String externalId) {
        return new RevolutAccountData(
            externalId, "Revolut EUR", AccountType.CHECKING, null,
            new BigDecimal("100.00"), "EUR", null, List.of());
    }

    private static DiscoveredRevolutAccount preview(String externalId) {
        return new DiscoveredRevolutAccount(
            externalId, "Revolut EUR", "CHECKING", "EUR", new BigDecimal("100.00"), null, false, 0);
    }

    // ─── startIfIdle: single-flight ──────────────────────────────────────────────

    @Test
    void startIfIdle_returnsTrueFirst_falseWhileRunning() {
        SyncProgressService service = new SyncProgressService();

        assertThat(service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT)).isTrue();
        assertThat(service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT)).isFalse();
    }

    @Test
    void startIfIdle_afterJobDone_allowsRestart() {
        SyncProgressService service = new SyncProgressService();
        service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT);
        service.done(MEMBER_ID, SyncProvider.REVOLUT);

        assertThat(service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT)).isTrue();
    }

    // ─── status: idle default ────────────────────────────────────────────────────

    @Test
    void status_idleDefault_whenNeverStarted() {
        SyncProgressService service = new SyncProgressService();

        SyncProgress status = service.status(MEMBER_ID, SyncProvider.REVOLUT);

        assertThat(status.running()).isFalse();
        assertThat(status.phase()).isNull();
        assertThat(status.done()).isFalse();
        assertThat(status.error()).isNull();
        assertThat(status.discovered()).isEmpty();
    }

    // ─── phase / counters ─────────────────────────────────────────────────────────

    @Test
    void phase_updatesPhaseRemainingElapsedAccountsFound_reflectedInStatus() {
        SyncProgressService service = new SyncProgressService();
        service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT);

        service.phase(MEMBER_ID, SyncProvider.REVOLUT, "AWAITING_APPROVAL", 42, 10, 3);

        SyncProgress status = service.status(MEMBER_ID, SyncProvider.REVOLUT);
        assertThat(status.phase()).isEqualTo("AWAITING_APPROVAL");
        assertThat(status.remainingSeconds()).isEqualTo(42);
        assertThat(status.elapsedSeconds()).isEqualTo(10);
        assertThat(status.accountsFound()).isEqualTo(3);
    }

    @Test
    void phase_threeArgOverload_updatesPhaseOnly_leavesOtherFieldsUnchanged() {
        SyncProgressService service = new SyncProgressService();
        service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT);
        service.phase(MEMBER_ID, SyncProvider.REVOLUT, "LOGGING_IN", 30, 5, 1);

        service.phase(MEMBER_ID, SyncProvider.REVOLUT, "HARVESTING");

        SyncProgress status = service.status(MEMBER_ID, SyncProvider.REVOLUT);
        assertThat(status.phase()).isEqualTo("HARVESTING");
        assertThat(status.remainingSeconds()).isEqualTo(30);
        assertThat(status.accountsFound()).isEqualTo(1);
    }

    @Test
    void accountsFound_updatesCountReflectedInStatus() {
        SyncProgressService service = new SyncProgressService();
        service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT);

        service.accountsFound(MEMBER_ID, SyncProvider.REVOLUT, 5);

        assertThat(service.status(MEMBER_ID, SyncProvider.REVOLUT).accountsFound()).isEqualTo(5);
    }

    // ─── Revolut discovery hand-off ───────────────────────────────────────────────

    @Test
    void setDiscovered_thenStatusDiscoveredReturnsPreview() {
        SyncProgressService service = new SyncProgressService();
        service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT);

        DiscoveredRevolutAccount preview = preview("wallet-1");
        RevolutAccountData raw = rawAccount("wallet-1");

        service.setDiscovered(MEMBER_ID, SyncProvider.REVOLUT, List.of(preview), List.of(raw));

        assertThat(service.status(MEMBER_ID, SyncProvider.REVOLUT).discovered()).containsExactly(preview);
    }

    @Test
    void takePendingDiscovery_returnsRawListOnce_thenEmpty() {
        SyncProgressService service = new SyncProgressService();
        service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT);

        RevolutAccountData raw = rawAccount("wallet-1");
        service.setDiscovered(MEMBER_ID, SyncProvider.REVOLUT, List.of(preview("wallet-1")), List.of(raw));

        assertThat(service.takePendingDiscovery(MEMBER_ID)).containsExactly(raw);
        assertThat(service.takePendingDiscovery(MEMBER_ID)).isEmpty();
    }

    @Test
    void takePendingDiscovery_neverStarted_returnsEmptyNotNull() {
        SyncProgressService service = new SyncProgressService();

        assertThat(service.takePendingDiscovery(MEMBER_ID)).isEmpty();
    }

    // ─── Terminal states ───────────────────────────────────────────────────────────

    @Test
    void done_flipsRunningFalse_andSetsDone() {
        SyncProgressService service = new SyncProgressService();
        service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT);

        service.done(MEMBER_ID, SyncProvider.REVOLUT);

        SyncProgress status = service.status(MEMBER_ID, SyncProvider.REVOLUT);
        assertThat(status.running()).isFalse();
        assertThat(status.done()).isTrue();
        assertThat(status.error()).isNull();
    }

    @Test
    void error_flipsRunningFalse_andSetsErrorMessage() {
        SyncProgressService service = new SyncProgressService();
        service.startIfIdle(MEMBER_ID, SyncProvider.REVOLUT);

        service.error(MEMBER_ID, SyncProvider.REVOLUT, "boom");

        SyncProgress status = service.status(MEMBER_ID, SyncProvider.REVOLUT);
        assertThat(status.running()).isFalse();
        assertThat(status.done()).isTrue();
        assertThat(status.error()).isEqualTo("boom");
    }
}
