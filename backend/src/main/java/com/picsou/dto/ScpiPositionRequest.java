package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.picsou.model.DistributionFrequency;
import com.picsou.model.DividendPolicy;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A manually entered SCPI position.
 *
 * <p>{@code withdrawalPriceEur} may be omitted. The service then keeps the previous balance
 * and reports {@code PRICE_INCOMPLETE}, rather than treating the subscription price as what
 * the shares could be sold for.
 *
 * <p>The distribution fields follow the fund-code convention: a key the caller did not send
 * leaves the stored value alone. An explicit JSON null clears it, the way an empty fund code
 * detaches a fund. {@code annualDistributionPerShareEur} is {@code NUMERIC(20,8)}.
 */
@JsonDeserialize(using = ScpiPositionRequest.Deserializer.class)
public record ScpiPositionRequest(
    @Size(max = 12) String isin,
    @Size(max = 100) String managementCompany,
    /** Links this account to one fund of a CORUM contract so a sync can fill it. */
    @Size(max = 40) String corumFundCode,
    /** Links this account to one fund of a Sofidy portfolio so a sync can fill it. */
    @Size(max = 40) String sofidyFundCode,
    @NotNull @DecimalMin("0") BigDecimal shareCount,
    @DecimalMin("0") BigDecimal subscriptionPriceEur,
    @DecimalMin("0") BigDecimal withdrawalPriceEur,
    DividendPolicy dividendPolicy,
    LocalDate jouissanceDate,
    /**
     * Annual cash per share, in euros. Omitted leaves the stored figure alone.
     * Present and null clears it. {@code @Digits} mirrors the column, NUMERIC(20, 8):
     * a larger value is a 400, not a 500.
     */
    @DecimalMin("0") @Digits(integer = 12, fraction = 8) BigDecimal annualDistributionPerShareEur,
    /** Monthly, quarterly, or null. Omitted leaves the stored cadence alone. */
    DistributionFrequency distributionFrequency,
    @JsonIgnore boolean annualDistributionSpecified,
    @JsonIgnore boolean distributionFrequencySpecified
) {
    /** Callers that omit the distribution fields. Null here is "not sent", not a clear. */
    public ScpiPositionRequest(
        String isin,
        String managementCompany,
        String corumFundCode,
        String sofidyFundCode,
        BigDecimal shareCount,
        BigDecimal subscriptionPriceEur,
        BigDecimal withdrawalPriceEur,
        DividendPolicy dividendPolicy,
        LocalDate jouissanceDate,
        BigDecimal annualDistributionPerShareEur,
        DistributionFrequency distributionFrequency
    ) {
        this(isin, managementCompany, corumFundCode, sofidyFundCode, shareCount,
            subscriptionPriceEur, withdrawalPriceEur, dividendPolicy, jouissanceDate,
            annualDistributionPerShareEur, distributionFrequency, false, false);
    }

    static final class Deserializer extends JsonDeserializer<ScpiPositionRequest> {
        @Override
        public ScpiPositionRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            boolean amountSpecified = node.has("annualDistributionPerShareEur");
            boolean frequencySpecified = node.has("distributionFrequency");
            return new ScpiPositionRequest(
                text(node, "isin"),
                text(node, "managementCompany"),
                text(node, "corumFundCode"),
                text(node, "sofidyFundCode"),
                decimal(node, "shareCount"),
                decimal(node, "subscriptionPriceEur"),
                decimal(node, "withdrawalPriceEur"),
                enumOf(node, "dividendPolicy", DividendPolicy.class),
                date(node, "jouissanceDate"),
                decimal(node, "annualDistributionPerShareEur"),
                enumOf(node, "distributionFrequency", DistributionFrequency.class),
                amountSpecified,
                frequencySpecified
            );
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return null;
            }
            return value.asText();
        }

        private static BigDecimal decimal(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || value.asText().isBlank()) {
                return null;
            }
            return new BigDecimal(value.asText());
        }

        private static LocalDate date(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || value.asText().isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(value.asText());
            } catch (java.time.format.DateTimeParseException ex) {
                // The handler maps IllegalArgumentException to 400, like a bad number or enum.
                throw new IllegalArgumentException("Invalid date for " + field, ex);
            }
        }

        private static <E extends Enum<E>> E enumOf(JsonNode node, String field, Class<E> type) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || value.asText().isBlank()) {
                return null;
            }
            return Enum.valueOf(type, value.asText());
        }
    }
}
