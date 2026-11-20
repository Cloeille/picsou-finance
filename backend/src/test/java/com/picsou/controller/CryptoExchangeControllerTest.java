package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.model.ExchangeType;
import com.picsou.port.CryptoExchangePort.ExchangeCredentials;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.CryptoExchangeSyncService.ExchangeStatusResponse;
import com.picsou.service.UserContext;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CryptoExchangeControllerTest {
    private final CryptoExchangeSyncService service = mock(CryptoExchangeSyncService.class);
    private final UserContext userContext = mock(UserContext.class);
    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CryptoExchangeController(service, userContext, factory.getValidator()))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @AfterEach
    void closeValidatorFactory() {
        factory.close();
    }

    @Test
    void addExchange_forwardsPassphraseAndRegionFromJson() throws Exception {
        when(userContext.currentMemberId()).thenReturn(1L);

        mvc.perform(post("/api/crypto/exchange").contentType("application/json")
                .content("""
                    {"type":"BINANCE","apiKey":"test-key","apiSecret":"test-secret","apiPassphrase":"test-passphrase","region":"EEA"}
                    """))
            .andExpect(status().isOk());

        verify(service).addExchange(ExchangeType.BINANCE,
            new ExchangeCredentials("test-key", "test-secret", "test-passphrase", "EEA"), 1L);
    }

    @Test
    void getStatus_includesTheStoredRegionInJson() throws Exception {
        when(userContext.currentMemberId()).thenReturn(1L);
        when(service.getStatus(1L)).thenReturn(List.of(
            new ExchangeStatusResponse(7L, ExchangeType.BINANCE, "CONNECTED", null, "EEA")));

        mvc.perform(get("/api/crypto/exchange/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].region").value("EEA"));
    }

    @Test
    void oversizedPassphraseIsRedactedFromValidationExceptionsAndResponses() throws Exception {
        String passphrase = "test-passphrase".repeat(14);

        var result = mvc.perform(post("/api/crypto/exchange").contentType("application/json")
                .content("""
                    {"type":"BINANCE","apiKey":"test-key","apiSecret":"test-secret","apiPassphrase":"%s"}
                    """.formatted(passphrase)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.apiPassphrase").isString())
            .andReturn();
        assertThat(result.getResolvedException()).hasMessageNotContaining(passphrase);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(passphrase);
        verifyNoInteractions(service);
    }
}
