package com.picsou.service;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.repository.RequisitionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists retry-critical requisition transitions independently of the account
 * synchronization transaction. The OAuth code is single-use, so the exchanged
 * session id and a later FAILED status must survive any account-upsert rollback.
 *
 * <p>This logic lives in a separate bean because Spring transaction propagation
 * is proxy-based: a self-invoked method on {@link SyncService} would not start a
 * new physical transaction.
 */
@Component
public class RequisitionLifecycleWriter {

    private final RequisitionRepository repository;

    public RequisitionLifecycleWriter(RequisitionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkpointSession(Long requisitionId, Long memberId, String sessionId) {
        Requisition requisition = getForMember(requisitionId, memberId);
        requisition.setRequisitionId(sessionId);
        requisition.setOauthState(null);
        repository.save(requisition);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long requisitionId, Long memberId) {
        Requisition requisition = getForMember(requisitionId, memberId);
        requisition.setStatus(RequisitionStatus.FAILED);
        repository.save(requisition);
    }

    private Requisition getForMember(Long requisitionId, Long memberId) {
        return repository.findByIdAndMemberId(requisitionId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));
    }
}
