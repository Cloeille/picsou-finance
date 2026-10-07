package com.picsou.service;

import com.picsou.dto.AiCallLogPage;
import com.picsou.dto.AiCallLogResponse;
import com.picsou.model.AiCallLog;
import com.picsou.repository.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiCallLogService {

    private static final int RETENTION = 2000;

    private final AiCallLogRepository repo;

    @Transactional
    public void saveAll(List<AiCallLog> rows) {
        repo.saveAll(rows);
    }

    @Transactional
    public void prune() {
        repo.pruneKeepingNewest(RETENTION);
    }

    @Transactional(readOnly = true)
    public AiCallLogPage list(int limit, int offset) {
        limit = Math.max(1, Math.min(200, limit));
        offset = Math.max(0, offset);
        int page = offset / limit;

        PageRequest pageRequest = PageRequest.of(page, limit);
        Page<AiCallLog> pageResult = repo.findAllByOrderByCreatedAtDesc(pageRequest);

        List<AiCallLogResponse> items = pageResult.getContent().stream()
            .map(AiCallLogResponse::from)
            .toList();

        return new AiCallLogPage(items, repo.count(), repo.sumTotalTokens());
    }
}
