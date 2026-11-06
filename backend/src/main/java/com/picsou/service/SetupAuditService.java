package com.picsou.service;

import com.picsou.model.SetupAudit;
import com.picsou.repository.SetupAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Writes rows to the append-only {@code setup_audit} table. Crash-safe by
 * design — it catches and logs its own exceptions, so audit hiccups never
 * block the setup flow itself. Audit is valuable when it works; never
 * worth blocking a legitimate user on.
 */
@Service
public class SetupAuditService {

    private static final Logger log = LoggerFactory.getLogger(SetupAuditService.class);
    private static final int MAX_UA_LEN = 500;

    private final SetupAuditRepository repository;
    private final TransactionTemplate ownTransaction;

    public SetupAuditService(SetupAuditRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Writes in a transaction of its own and catches failures outside it: a failed insert then
     * marks only that transaction rollback-only, never the caller's, so it cannot undo the
     * operation it records.
     */
    public void record(String event, String actorUsername, HttpServletRequest request, String details) {
        try {
            String ip = request != null ? request.getRemoteAddr() : null;
            String ua = request != null ? request.getHeader("User-Agent") : null;
            if (ua != null && ua.length() > MAX_UA_LEN) {
                ua = ua.substring(0, MAX_UA_LEN);
            }
            SetupAudit row = SetupAudit.builder()
                .event(event)
                .actorUsername(actorUsername)
                .ip(ip)
                .userAgent(ua)
                .details(details)
                .at(Instant.now())
                .build();
            ownTransaction.executeWithoutResult(status -> repository.save(row));
        } catch (RuntimeException ex) {
            log.warn("setup_audit.write_failed event={} err={}", event, ex.getMessage());
        }
    }
}
