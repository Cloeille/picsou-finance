package com.picsou.service;

import com.picsou.repository.SimplefinConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a SimpleFIN connection's {@code ERROR} status in its own transaction,
 * so a rolled-back sync still leaves a visible failure. Same reason as
 * {@link IbkrStatusWriter}: the caller is {@code @Transactional} and rethrows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimplefinStatusWriter {

    private final SimplefinConnectionRepository connectionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markError(Long connectionId) {
        connectionRepository.findById(connectionId).ifPresentOrElse(connection -> {
            connection.setStatus("ERROR");
            connectionRepository.save(connection);
        }, () -> log.error("Cannot mark SimpleFIN connection {} as ERROR: row not found", connectionId));
    }
}
