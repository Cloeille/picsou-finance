package com.picsou.port;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared BPCE engine converts its codes to each bank's persisted enum by name. */
class BpceErrorCodeTest {

    @Test
    void everyBankEnumCarriesExactlyTheSharedCodesInTheSameOrder() {
        assertThat(CaisseEpargneErrorCode.values()).extracting(Enum::name)
            .containsExactly(names(BpceErrorCode.values()));
        assertThat(BanquePopulaireErrorCode.values()).extracting(Enum::name)
            .containsExactly(names(BpceErrorCode.values()));
    }

    private static String[] names(Enum<?>[] values) {
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name();
        }
        return names;
    }
}
