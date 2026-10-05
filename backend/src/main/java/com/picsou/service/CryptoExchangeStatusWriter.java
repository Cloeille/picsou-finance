package com.picsou.service;

import com.picsou.repository.CryptoExchangeSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a crypto exchange session's {@code ERROR} status in its own transaction.
 *
 * <p>{@link CryptoExchangeSyncService} is {@code @Transactional}; when a manual sync fails, the
 * rethrown exception marks that transaction rollback-only, so a plain save of the {@code ERROR}
 * status made inside {@code sync} is silently discarded on the manual path (the scheduled path
 * happens to survive because {@code resyncAll} catches the exception before it crosses the proxy
 * — the two paths must not disagree, or a revoked API key shows as {@code CONNECTED} forever
 * when the user syncs by hand and as {@code ERROR} when the scheduler does). This bean's
 * {@code REQUIRES_NEW} method commits independently of the caller's transaction. Must be a
 * separate Spring bean: a {@code REQUIRES_NEW} method invoked via {@code this} would not cross
 * the proxy and would have no effect. Mirrors {@link IbkrStatusWriter}.
 *
 * <p><b>Call it once the caller's transaction is over, not from inside it.</b> A second
 * transaction updating the session row waits for the first one's row lock, and the first one
 * holds that lock as soon as it has touched the row — which {@code addExchange} does, and which
 * any query against {@code crypto_exchange_session} then flushes. Waiting here would block on a
 * lock only the caller can release while the caller waits for this method to return; Postgres
 * sees no cycle, so the request hangs until a {@code lock_timeout} that is unset by default.
 * {@code CryptoExchangeSyncService.markErrorWhenThisTransactionEnds} defers the call to
 * {@code afterCompletion} for exactly that reason.
 */
@Service
public class CryptoExchangeStatusWriter {

    private static final Logger log = LoggerFactory.getLogger(CryptoExchangeStatusWriter.class);

    private final CryptoExchangeSessionRepository sessionRepository;

    public CryptoExchangeStatusWriter(CryptoExchangeSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markError(Long sessionId) {
        sessionRepository.findById(sessionId).ifPresentOrElse(session -> {
            session.setStatus("ERROR");
            sessionRepository.save(session);
        // Not an error: this transaction cannot see rows the caller's transaction has not
        // committed, and `addExchange` syncs a session it has just inserted. When that sync
        // fails the insert is rolled back too, so there is no row to mark and nothing stale is
        // left behind — the same is true of a session deleted mid-sync.
        }, () -> log.debug("No exchange session {} to mark as ERROR: it is not committed yet "
            + "(a failed first sync rolls its insert back) or was deleted", sessionId));
    }
}
