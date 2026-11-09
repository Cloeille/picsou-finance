package com.picsou.dto;

import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TransactionResponse(
    Long id,
    LocalDate date,
    String description,
    BigDecimal amount,
    String type,
    String category,
    String nativeCurrency,
    Instant createdAt,
    boolean isManual,
    TransactionType txType,
    String ticker,
    String name,
    BigDecimal quantity,
    BigDecimal pricePerUnit,
    Long categoryId,
    String categoryName,
    String counterparty,
    String merchantLabel,
    Long merchantBrandId,
    Long aiSuggestedCategoryId,
    Integer aiConfidence,
    Long accountId,
    String accountName,
    BigDecimal fees,
    /** Moves money inside the wrapper only; never part of the budget (see {@code Transaction}). */
    boolean budgetNeutral,
    /**
     * The peer loan this row belongs to, if any: a lending outflow or a linked repayment.
     * The transaction sheet uses it to hide the link actions (they could only fail) and
     * to point at the loan instead.
     */
    PeerLoanLink peerLoanLink
) {
    /** A transaction's role in a peer loan, if it has one. */
    public record PeerLoanLink(PeerLoanLinkKind kind, Long accountId, String accountName) {}

    /** Serialized as its name, so the API shape is unchanged ("LENDING" | "REPAYMENT"). */
    public enum PeerLoanLinkKind {
        LENDING,
        REPAYMENT
    }

    /** Maps a transaction to its API shape, flattening the category and account to ids and names. */
    public static TransactionResponse from(Transaction t) {
        return from(t, null);
    }

    /** Same mapping with the peer-loan link the caller resolved in batch. */
    public static TransactionResponse from(Transaction t, PeerLoanLink peerLoanLink) {
        return new TransactionResponse(
            t.getId(),
            t.getDate(),
            t.getDescription(),
            t.getAmount(),
            t.getType(),
            t.getCategory(),
            t.getNativeCurrency(),
            t.getCreatedAt(),
            t.isManual(),
            t.getTxType(),
            t.getTicker(),
            t.getName(),
            t.getQuantity(),
            t.getPricePerUnit(),
            t.getCategoryRef() != null ? t.getCategoryRef().getId() : null,
            t.getCategoryRef() != null ? t.getCategoryRef().getName() : null,
            t.getCounterparty(),
            t.getMerchantLabel(),
            t.getMerchantBrandId(),
            t.getAiSuggestedCategoryId(),
            t.getAiConfidence(),
            t.getAccount().getId(),
            t.getAccount().getName(),
            t.getFees(),
            t.isBudgetNeutral(),
            peerLoanLink
        );
    }
}
