package com.picsou.repository;

import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RecurringSeriesRepository extends JpaRepository<RecurringSeries, Long> {

    List<RecurringSeries> findAllByMemberIdOrderByNextDueDateAsc(Long memberId);

    List<RecurringSeries> findAllByMemberIdAndStatusOrderByNextDueDateAsc(Long memberId, RecurringStatus status);

    Optional<RecurringSeries> findByIdAndMemberId(Long id, Long memberId);

    /**
     * Detection identity (v2): re-find an existing series by its stable clean label (any status).
     * Backed by the unique index {@code (member_id, lower(label))}.
     */
    Optional<RecurringSeries> findByMemberIdAndLabelIgnoreCase(Long memberId, String label);

    /** Confirmed series whose projected due date has fallen within the calendar window. */
    List<RecurringSeries> findAllByMemberIdAndStatusAndNextDueDateLessThanEqualOrderByNextDueDateAsc(
        Long memberId, RecurringStatus status, LocalDate through);
}
