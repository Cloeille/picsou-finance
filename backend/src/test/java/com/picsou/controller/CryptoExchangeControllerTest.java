package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.UserContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CryptoExchangeControllerTest {
    @Test
    void oversizedPassphraseIsRedactedFromValidationExceptionsAndResponses() throws Exception {
        var service = mock(CryptoExchangeSyncService.class);
        String passphrase = "test-passphrase".repeat(14);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var controller = new CryptoExchangeController(service, mock(UserContext.class), factory.getValidator());
            var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
            var result = mvc.perform(post("/api/crypto/exchange").contentType("application/json")
                    .content("""
                        {"type":"BINANCE","apiKey":"test-key","apiSecret":"test-secret","apiPassphrase":"%s"}
                        """.formatted(passphrase)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.apiPassphrase").isString())
                .andReturn();
            assertThat(result.getResolvedException()).hasMessageNotContaining(passphrase);
            assertThat(result.getResponse().getContentAsString()).doesNotContain(passphrase);
        }
        verifyNoInteractions(service);
    }
}
