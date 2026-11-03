package com.picsou.service;

import com.picsou.repository.WiseConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a failed Wise sync's status in its own transaction, so a rolled-back sync still
 * leaves a visible failure. Same reason as {@link SimplefinStatusWriter}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WiseStatusWriter {

    private final WiseConnectionRepository connectionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markError(Long connectionId) {
        mark(connectionId, WiseSyncService.STATUS_ERROR);
    }

    /** Wise refused the token: only a new one fixes it, so retrying is pointless. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReauthRequired(Long connectionId) {
        mark(connectionId, WiseSyncService.STATUS_REAUTH_REQUIRED);
    }

    private void mark(Long connectionId, String status) {
        connectionRepository.findById(connectionId).ifPresentOrElse(connection -> {
            connection.setStatus(status);
            connectionRepository.save(connection);
        }, () -> log.error("Cannot mark Wise connection {} as {}: row not found", connectionId, status));
    }
}
