package com.picsou.controller;

import com.picsou.service.AccountConnectionService;
import com.picsou.service.AccountOwnershipService;
import com.picsou.service.AccountService;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.HoldingClassificationService;
import com.picsou.service.ManualTransactionService;
import com.picsou.service.PropertyValuationService;
import com.picsou.service.RealizedPnlService;
import com.picsou.service.ScpiPositionService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bean validation on the SCPI body. Spring's default mapping of a failed {@code @Valid}
 * is 400; this app's {@code GlobalExceptionHandler} turns the same failure into 422.
 * Either way it never reaches the database as a 500.
 */
@ExtendWith(MockitoExtension.class)
class ScpiPositionRequestValidationTest {

    @Mock AccountService accountService;
    @Mock UserContext userContext;
    @Mock ManualTransactionService manualTransactionService;
    @Mock RealizedPnlService realizedPnlService;
    @Mock CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock PropertyValuationService propertyValuationService;
    @Mock AccountOwnershipService ownershipService;
    @Mock AccountConnectionService accountConnectionService;
    @Mock HoldingClassificationService holdingClassificationService;
    @Mock ScpiPositionService scpiPositionService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AccountController controller = new AccountController(
            accountService, userContext, manualTransactionService, realizedPnlService,
            cryptoExchangeSyncService, propertyValuationService, ownershipService,
            accountConnectionService, holdingClassificationService, scpiPositionService);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
    }

    @Test
    void anOversizedAnnualDistributionIsABadRequest() throws Exception {
        mvc.perform(put("/api/accounts/10/scpi")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"shareCount":10,"annualDistributionPerShareEur":1000000000000}
                    """))
            .andExpect(status().isBadRequest());

        verify(scpiPositionService, never()).save(any(), any(), any());
    }
}
