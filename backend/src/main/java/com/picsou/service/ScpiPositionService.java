package com.picsou.service;

import com.picsou.dto.ScpiPositionRequest;
import com.picsou.dto.ScpiPositionResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.DividendPolicy;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiValuationStatus;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.ScpiPositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Records a SCPI share and, when the withdrawal price is known, writes the account balance
 * from it. The quantity is stored here, not rebuilt from BUY/SELL, so a later sync can
 * replace it without fighting {@link AccountType#isInvestment()}.
 */
@Service
public class ScpiPositionService {

    private static final int MONEY_SCALE = 8;

    private final AccountRepository accountRepository;
    private final ScpiPositionRepository positionRepository;

    public ScpiPositionService(AccountRepository accountRepository, ScpiPositionRepository positionRepository) {
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
    }

    @Transactional
    public ScpiPositionResponse save(Long accountId, Long memberId, ScpiPositionRequest request) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> com.picsou.exception.ResourceNotFoundException.account(accountId));
        if (account.getType() != AccountType.SCPI) {
            throw new IllegalArgumentException("Account is not a SCPI account");
        }
        account.setCurrency("EUR");

        ScpiPosition position = positionRepository.findByAccountIdAndMemberId(accountId, memberId)
            .orElseGet(() -> ScpiPosition.builder()
                .account(account)
                .member(account.getMember())
                .build());

        position.setIsin(blankToNull(request.isin()));
        position.setManagementCompany(blankToNull(request.managementCompany()));
        // A field the caller did not send means "leave it alone", not "clear it".
        // Partial callers may omit provider links when correcting a position.
        // Writing unconditionally would quietly remove it from later syncs.
        // An explicit empty string is how a user detaches a fund on purpose.
        if (request.corumFundCode() != null) {
            position.setCorumFundCode(blankToNull(request.corumFundCode()));
        }
        if (request.sofidyFundCode() != null) {
            position.setSofidyFundCode(blankToNull(request.sofidyFundCode()));
        }
        position.setShareCount(request.shareCount());
        position.setSubscriptionPriceEur(request.subscriptionPriceEur());
        position.setWithdrawalPriceEur(request.withdrawalPriceEur());
        position.setDividendPolicy(request.dividendPolicy() != null ? request.dividendPolicy() : DividendPolicy.CASH);
        position.setJouissanceDate(request.jouissanceDate());

        BigDecimal withdrawalValue = withdrawalValue(request.shareCount(), request.withdrawalPriceEur());
        if (withdrawalValue == null) {
            // A missing withdrawal price is not a licence to use the subscription price.
            // Entry fees sit between the two, so that substitution would overstate net worth.
            position.setValuationStatus(ScpiValuationStatus.PRICE_INCOMPLETE);
            positionRepository.save(position);
            accountRepository.save(account);
            return ScpiPositionResponse.from(position, null);
        }

        position.setValuationStatus(ScpiValuationStatus.OK);
        account.setCurrentBalance(withdrawalValue);
        account.setCashBalance(BigDecimal.ZERO);
        positionRepository.save(position);
        accountRepository.save(account);
        return ScpiPositionResponse.from(position, withdrawalValue);
    }

    /**
     * Applies a synchronised CORUM holding onto an existing position.
     *
     * <p>This is the same write path {@link #save} performs, reached from a sync
     * instead of a form. It exists so the withdrawal-price rule lives in one
     * place: a sync that valued the share any other way would quietly
     * reintroduce the entry-fee overstatement the manual model exists to avoid.
     *
     * <p>A null {@code withdrawalPrice} is not an error and not a zero -- CORUM
     * is not quoting that fund today. The share count and the subscription price
     * are still real and get written, the previous balance is left alone, and the
     * position reports {@code PRICE_INCOMPLETE} so the UI can say why.
     *
     * <p>A null {@code subscriptionPrice} means "this sync says nothing about
     * it" and leaves the stored value alone. Sofidy publishes no subscription
     * price at all, so writing null through would delete a price the user typed
     * by hand on every sync. Clearing it stays a manual action.
     */
    @Transactional
    public void applySyncedPosition(
        ScpiPosition position,
        BigDecimal shareCount,
        BigDecimal subscriptionPrice,
        BigDecimal withdrawalPrice,
        java.time.LocalDate jouissanceDate
    ) {
        Account account = position.getAccount();
        if (account.getType() != AccountType.SCPI) {
            // The fund link is data, and data can be wrong. Refusing here keeps a
            // bad link from writing a share balance onto another asset type.
            throw new IllegalArgumentException("Account is not a SCPI account");
        }
        account.setCurrency("EUR");

        position.setShareCount(shareCount);
        if (subscriptionPrice != null) {
            position.setSubscriptionPriceEur(subscriptionPrice);
        }
        position.setWithdrawalPriceEur(withdrawalPrice);
        if (jouissanceDate != null) {
            position.setJouissanceDate(jouissanceDate);
        }

        BigDecimal withdrawalValue = withdrawalValue(shareCount, withdrawalPrice);
        if (withdrawalValue == null) {
            position.setValuationStatus(ScpiValuationStatus.PRICE_INCOMPLETE);
        } else {
            position.setValuationStatus(ScpiValuationStatus.OK);
            account.setCurrentBalance(withdrawalValue);
            account.setCashBalance(BigDecimal.ZERO);
        }
        positionRepository.save(position);
        accountRepository.save(account);
    }

    /**
     * Null when the withdrawal price is absent. Zero shares is a real value, not a
     * missing one.
     *
     * <p>Zero shares is checked before the price on purpose: a sold position has
     * no value whatever price is or is not quoted, so "0 shares and no price"
     * means zero, not unknown. Reading it as unknown would leave the sold
     * balance standing in the net worth.
     */
    static BigDecimal withdrawalValue(BigDecimal shareCount, BigDecimal withdrawalPrice) {
        if (shareCount == null) {
            return null;
        }
        if (shareCount.signum() == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        if (withdrawalPrice == null) {
            return null;
        }
        return shareCount.multiply(withdrawalPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
