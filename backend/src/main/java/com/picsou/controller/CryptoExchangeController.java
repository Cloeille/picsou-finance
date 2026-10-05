package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.model.ExchangeType;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crypto/exchange")
public class CryptoExchangeController {

    private final CryptoExchangeSyncService exchangeService;
    private final UserContext userContext;

    public CryptoExchangeController(CryptoExchangeSyncService exchangeService, UserContext userContext) {
        this.exchangeService = exchangeService;
        this.userContext = userContext;
    }

    @PostMapping
    public AccountResponse addExchange(@Valid @RequestBody AddExchangeRequest req) {
        return exchangeService.addExchange(req.type(), req.apiKey(), req.apiSecret(), userContext.currentMemberId());
    }

    @PostMapping("/{id}/sync")
    public AccountResponse sync(@PathVariable Long id) {
        return exchangeService.sync(id, userContext.currentMemberId());
    }

    @GetMapping("/status")
    public List<CryptoExchangeSyncService.ExchangeStatusResponse> getStatus() {
        return exchangeService.getStatus(userContext.currentMemberId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeExchange(@PathVariable Long id) {
        exchangeService.removeExchange(id, userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Whether {@code apiSecret} is required, forbidden or ignored depends on the exchange's
     * adapter ({@code CryptoExchangePort.requiresApiSecret()}), which bean validation cannot
     * reach — {@code CryptoExchangeSyncService.addExchange} enforces that rule and returns 400.
     *
     * <p>Deliberately no {@code @NotBlank} on {@code apiKey}: bean validation failures map to a
     * 422 whose ProblemDetail carries an {@code errors} map but no {@code detail}, and the
     * frontend only reads {@code detail} — so a blank key would surface as an unexplained error
     * instead of the service's "An API key is required." 400.
     *
     * <p>The {@code @Size} bounds are on the <em>plaintext</em>, while the columns hold the
     * AES-GCM ciphertext: Base64 turns n bytes into roughly {@code 4/3 * (n + 28)} characters, so
     * 200 &rarr; ~304 and 300 &rarr; ~437, both inside {@code varchar(500)}. Raising either bound
     * without widening the column turns a long-but-valid credential into a 500 at INSERT.
     */
    record AddExchangeRequest(
        @NotNull ExchangeType type,
        @Size(max = 200) String apiKey,
        @Size(max = 300) String apiSecret) {}
}
