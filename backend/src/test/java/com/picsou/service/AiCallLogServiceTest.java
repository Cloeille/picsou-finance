package com.picsou.service;

import com.picsou.dto.AiCallLogPage;
import com.picsou.model.AiCallLog;
import com.picsou.repository.AiCallLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCallLogServiceTest {

    @Mock
    AiCallLogRepository repo;

    @InjectMocks
    AiCallLogService service;

    @Test
    void saveAll_delegates() {
        List<AiCallLog> rows = List.of(
            AiCallLog.builder().provider("openai").status("ok").build()
        );
        service.saveAll(rows);
        verify(repo).saveAll(rows);
    }

    @Test
    void prune_keepsNewest2000() {
        service.prune();
        verify(repo).pruneKeepingNewest(2000);
    }

    @Test
    void list_buildsPageRequestAndMaps() {
        AiCallLog entity = AiCallLog.builder()
            .id(1L)
            .provider("openai")
            .model("gpt-4o-mini")
            .status("ok")
            .createdAt(Instant.now())
            .merchantLabel("Carrefour")
            .promptTokens(10)
            .completionTokens(20)
            .totalTokens(30)
            .latencyMs(500)
            .applied(false)
            .build();

        Page<AiCallLog> pageResult = new PageImpl<>(List.of(entity));
        when(repo.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(pageResult);
        when(repo.count()).thenReturn(5L);
        when(repo.sumTotalTokens()).thenReturn(1234L);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        AiCallLogPage result = service.list(50, 0);

        verify(repo).findAllByOrderByCreatedAtDesc(pageableCaptor.capture());
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageSize()).isEqualTo(50);
        assertThat(captured.getPageNumber()).isEqualTo(0);

        assertThat(result.total()).isEqualTo(5L);
        assertThat(result.totalTokens()).isEqualTo(1234L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).provider()).isEqualTo("openai");
        assertThat(result.items().get(0).merchantLabel()).isEqualTo("Carrefour");
    }

    @Test
    void list_clampsLimit() {
        Page<AiCallLog> emptyPage = new PageImpl<>(List.of());
        when(repo.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(emptyPage);
        when(repo.count()).thenReturn(0L);
        when(repo.sumTotalTokens()).thenReturn(0L);

        // Over-large limit is clamped to 200
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        service.list(9999, 0);
        verify(repo).findAllByOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageSize()).isLessThanOrEqualTo(200);
    }

    @Test
    void list_negativeOffsetTreatedAsZero() {
        Page<AiCallLog> emptyPage = new PageImpl<>(List.of());
        when(repo.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(emptyPage);
        when(repo.count()).thenReturn(0L);
        when(repo.sumTotalTokens()).thenReturn(0L);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        service.list(50, -10);
        verify(repo).findAllByOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
    }
}
