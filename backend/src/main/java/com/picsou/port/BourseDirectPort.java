package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.util.List;

public interface BourseDirectPort {
    SidecarInitiate initiateAuth(String login, String password);
    String completeAuth(String processId, String code);
    List<AccountData> fetchAccounts(String sessionState);

    record Position(String isin, String symbol, String label, BigDecimal quantity,
                    BigDecimal buyingPriceEur, BigDecimal currentPrice, String quoteCurrency,
                    BigDecimal currentValueEur, BigDecimal pnlEur) {}
    record AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur,
                       BigDecimal cashBalance, List<Position> positions, boolean snapshotComplete) {}
}
