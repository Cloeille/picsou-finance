package com.picsou.service;

import com.picsou.service.budget.InternalTransferPairingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs the passes that read across a member's freshly imported rows once an import has
 * committed: after the synchronous sources of a member sync, and at the end of each queued
 * broker job, whose rows land after the member sync has returned.
 *
 * <ol>
 *   <li>{@link DividendReceiptService#classifyRecent}: cash credits that match a dividend.</li>
 *   <li>{@link InternalTransferPairingService#pairRecent}: the two legs of a transfer between two
 *       of the member's accounts. It runs second so a recognised dividend is never paired.</li>
 * </ol>
 *
 * <p>Best effort: each pass is isolated, a failure is logged and never changes the sync outcome.
 * It sits outside both services so the catch also covers their commit.
 */
@Component
public class ReceivedDividendClassifier {
    private static final Logger log = LoggerFactory.getLogger(ReceivedDividendClassifier.class);

    private final DividendReceiptService receipts;
    private final InternalTransferPairingService transfers;

    public ReceivedDividendClassifier(DividendReceiptService receipts, InternalTransferPairingService transfers) {
        this.receipts = receipts;
        this.transfers = transfers;
    }

    public void afterImport(Long memberId) {
        LocalDate today = LocalDate.now();
        try {
            receipts.classifyRecent(memberId, today);
        } catch (RuntimeException ex) {
            log.error("Dividend classification failed for member {}", memberId, ex);
        }
        try {
            transfers.pairRecent(memberId, today);
        } catch (RuntimeException ex) {
            log.error("Internal transfer pairing failed for member {}", memberId, ex);
        }
    }
}
