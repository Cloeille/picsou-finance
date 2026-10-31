package com.picsou.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs {@link DividendReceiptService#classifyRecent} once an import has committed: after the
 * synchronous sources of a member sync, and at the end of each queued broker job, whose rows
 * land after the member sync has returned.
 *
 * <p>Best effort: a failure is logged and never changes the sync outcome. It sits outside
 * {@link DividendReceiptService} so the catch also covers that service's commit.
 */
@Component
public class ReceivedDividendClassifier {
    private static final Logger log = LoggerFactory.getLogger(ReceivedDividendClassifier.class);

    private final DividendReceiptService receipts;

    public ReceivedDividendClassifier(DividendReceiptService receipts) {
        this.receipts = receipts;
    }

    public void afterImport(Long memberId) {
        try {
            receipts.classifyRecent(memberId, LocalDate.now());
        } catch (RuntimeException ex) {
            log.error("Dividend classification failed for member {}", memberId, ex);
        }
    }
}
