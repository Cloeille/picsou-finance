package com.picsou.service;

import com.picsou.model.DegiroSessionStatus;
import com.picsou.repository.DegiroSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a DEGIRO session's {@code REAUTH_REQUIRED} status in its own transaction.
 *
 * <p>{@link DegiroSyncService} is {@code @Transactional}; when a sync hits an expired
 * session it flips the status and then rethrows, which marks that transaction
 * rollback-only — so a plain save made inside {@code syncWithBlob} is silently
 * discarded. The next sync would then re-read {@code ACTIVE}, sail past the
 * {@code REAUTH_REQUIRED} guard in {@code sync()} and call the sidecar again, every
 * time, instead of telling the user to reconnect. This bean's {@code REQUIRES_NEW}
 * method commits independently of the caller's transaction, so the status write
 * survives the rollback.
 *
 * <p>Must be a separate Spring bean, mirroring {@link IbkrStatusWriter}: a
 * {@code REQUIRES_NEW} method invoked via {@code this} inside {@code DegiroSyncService}
 * would not cross the proxy and would have no effect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DegiroSessionStatusWriter {

    private final DegiroSessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReauthRequired(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresentOrElse(s -> {
            s.setStatus(DegiroSessionStatus.REAUTH_REQUIRED);
            s.setLastError("SESSION_EXPIRED");
            sessionRepository.save(s);
        }, () -> log.error("Cannot mark DEGIRO session for member {} as REAUTH_REQUIRED: row not found "
            + "(session cleared mid-sync?) — the user will not be prompted to reconnect", memberId));
    }
}
