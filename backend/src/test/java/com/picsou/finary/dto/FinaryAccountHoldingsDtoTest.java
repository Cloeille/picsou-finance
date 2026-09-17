package com.picsou.finary.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinaryAccountHoldingsDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void accountEnvelope_parsesHoldingsAndDisplayBalance() throws Exception {
        String json = """
            {
              "result": [
                {
                  "id": "acc-1",
                  "name": "Yomoni PER",
                  "balance": 245920.75,
                  "display_balance": 245920.75,
                  "currency": { "code": "EUR", "symbol": "€" },
                  "securities": [
                    {
                      "quantity": 191.27,
                      "buying_value": 21774.18,
                      "current_value": 23187.66,
                      "unrealized_pnl": 1413.48,
                      "display_buying_value": 21774.18,
                      "display_current_value": 23187.66,
                      "display_unrealized_pnl": 1413.48,
                      "security": {
                        "name": "Yomoni Allocation ISR P",
                        "isin": "FR0050000282",
                        "symbol": "0P0001PK78"
                      }
                    }
                  ],
                  "fiats": [],
                  "cryptos": [],
                  "fonds_euro": []
                }
              ]
            }
            """;

        FinaryEnvelope<List<FinaryAccountDto>> envelope = objectMapper.readValue(json,
            objectMapper.getTypeFactory().constructParametricType(
                FinaryEnvelope.class,
                objectMapper.getTypeFactory().constructCollectionType(List.class, FinaryAccountDto.class)));

        FinaryAccountDto acc = envelope.result().get(0);
        assertThat(acc.displayBalance()).isEqualTo(245920.75);
        assertThat(acc.securities()).hasSize(1);
        FinaryPositionDto line = acc.securities().get(0);
        assertThat(line.security().isin()).isEqualTo("FR0050000282");
        assertThat(line.displayCurrentValue()).isEqualTo(23187.66);
        assertThat(acc.fondsEuro()).isEmpty();
    }
}
