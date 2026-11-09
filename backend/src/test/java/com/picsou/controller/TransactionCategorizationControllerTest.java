package com.picsou.controller;

import com.picsou.dto.AiJobStatus;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.UserContext;
import com.picsou.service.budget.AiCategorizationJobService;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionCategorizationControllerTest {

    @Mock CategorizationService categorizationService;
    @Mock AiCategorizationJobService jobService;
    @Mock UserContext userContext;
    @Mock TransactionRepository transactionRepository;

    TransactionCategorizationController controller;

    private static final Long MEMBER_ID = 42L;

    @BeforeEach
    void setUp() {
        controller = new TransactionCategorizationController(categorizationService, jobService, userContext, transactionRepository);
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void categorizeWithAi_returns202_withJobStatusFromStart() {
        AiJobStatus status = new AiJobStatus(true, 10, 0, 0, 0, false, null);
        when(jobService.start(MEMBER_ID)).thenReturn(status);

        ResponseEntity<AiJobStatus> response = controller.categorizeWithAi();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(status);
    }

    @Test
    void categorizeAiStatus_returnsStatusFromJobService() {
        AiJobStatus status = new AiJobStatus(false, 10, 10, 7, 3, true, null);
        when(jobService.status(MEMBER_ID)).thenReturn(status);

        AiJobStatus result = controller.categorizeAiStatus();

        assertThat(result).isEqualTo(status);
    }
}
