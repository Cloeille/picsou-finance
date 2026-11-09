package com.picsou.dto;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AccountResponseInstitutionTest {

    private static AccountResponse response(String name, String domain) {
        Account account = Account.builder().id(1L).name("HSA").type(AccountType.BROKERAGE).provider("SimpleFIN")
            .currency("USD").institutionName(name).institutionDomain(domain).build();
        return AccountResponse.from(account, BigDecimal.TEN);
    }

    @Test
    void from_carriesTheInstitutionAndWhetherALogoCanBeAskedFor() {
        assertThat(response("Fidelity Investments", "fidelity.com").institution())
            .isEqualTo(new InstitutionResponse("Fidelity Investments", true));
    }

    @Test
    void from_anAccountWithoutAnInstitution_hasNone() {
        assertThat(response(null, null).institution()).isNull();
    }

    @Test
    void withoutInstitutionLogo_keepsTheNameAndDropsThePromiseOfALogo() {
        AccountResponse coOwnerView = response("Fidelity Investments", "fidelity.com").withoutInstitutionLogo();

        assertThat(coOwnerView.institution()).isEqualTo(new InstitutionResponse("Fidelity Investments", false));
    }

    @Test
    void withoutInstitutionLogo_isANoOpWithNoInstitution() {
        AccountResponse plain = response(null, null);

        assertThat(plain.withoutInstitutionLogo()).isSameAs(plain);
    }
}
