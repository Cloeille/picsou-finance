package com.picsou.service;

import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.repository.RequisitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequisitionLifecycleWriterTest {

    @Mock RequisitionRepository repository;

    @Test
    void checkpointSessionStoresSessionAndConsumesNonce() {
        Requisition requisition = Requisition.builder()
            .id(10L)
            .requisitionId("authorization-id")
            .oauthState("state-x")
            .status(RequisitionStatus.CREATED)
            .build();
        when(repository.findByIdAndMemberId(10L, 1L)).thenReturn(Optional.of(requisition));

        new RequisitionLifecycleWriter(repository).checkpointSession(10L, 1L, "session-id");

        assertThat(requisition.getRequisitionId()).isEqualTo("session-id");
        assertThat(requisition.getOauthState()).isNull();
        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.CREATED);
        verify(repository).save(requisition);
    }

    @Test
    void markFailedPreservesSessionAndNonce() {
        Requisition requisition = Requisition.builder()
            .id(10L)
            .requisitionId("session-id")
            .oauthState("state-x")
            .status(RequisitionStatus.CREATED)
            .build();
        when(repository.findByIdAndMemberId(10L, 1L)).thenReturn(Optional.of(requisition));

        new RequisitionLifecycleWriter(repository).markFailed(10L, 1L);

        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.FAILED);
        assertThat(requisition.getRequisitionId()).isEqualTo("session-id");
        assertThat(requisition.getOauthState()).isEqualTo("state-x");
        verify(repository).save(requisition);
    }

    @Test
    void retryCriticalWritesRequireIndependentTransactions() throws Exception {
        Method checkpoint = RequisitionLifecycleWriter.class.getMethod(
            "checkpointSession", Long.class, Long.class, String.class
        );
        Method markFailed = RequisitionLifecycleWriter.class.getMethod(
            "markFailed", Long.class, Long.class
        );

        assertThat(checkpoint.getAnnotation(Transactional.class).propagation())
            .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(markFailed.getAnnotation(Transactional.class).propagation())
            .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
