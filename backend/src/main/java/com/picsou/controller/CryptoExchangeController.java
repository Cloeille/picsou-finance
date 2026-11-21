package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.model.ExchangeType;
import com.picsou.port.CryptoExchangePort.ExchangeCredentials;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.UserContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crypto/exchange")
public class CryptoExchangeController {

    private final CryptoExchangeSyncService exchangeService;
    private final UserContext userContext;
    private final SpringValidatorAdapter credentialValidator;

    public CryptoExchangeController(CryptoExchangeSyncService exchangeService, UserContext userContext, Validator validator) {
        this.exchangeService = exchangeService;
        this.userContext = userContext;
        this.credentialValidator = new SpringValidatorAdapter(validator) {
            @Override
            protected Object getRejectedValue(String field, ConstraintViolation<Object> violation, BindingResult binding) {
                return null;
            }
        };
    }

    @InitBinder("addExchangeRequest")
    void redactRejectedCredentials(WebDataBinder binder) {
        binder.replaceValidators(credentialValidator);
    }

    @PostMapping
    public AccountResponse addExchange(@Valid @RequestBody AddExchangeRequest req) {
        return exchangeService.addExchange(req.type(),
            new ExchangeCredentials(req.apiKey(), req.apiSecret(), req.apiPassphrase(), req.region()),
            userContext.currentMemberId());
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
     * The exchange adapter declares whether a secret, passphrase or region is required.
     * {@code CryptoExchangeSyncService.addExchange} enforces those rules with a 400.
     *
     * <p>Deliberately no {@code @NotBlank} on {@code apiKey}: bean validation failures map to a
     * 422 whose ProblemDetail carries an {@code errors} map but no {@code detail}, and the
     * frontend only reads {@code detail} — so a blank key would surface as an unexplained error
     * instead of the service's "An API key is required." 400.
     *
     * <p>The credential {@code @Size} bounds are on the <em>plaintext</em>, while the columns hold the
     * AES-GCM ciphertext: Base64 encodes n UTF-8 bytes as {@code 4 * ceil((n + 28) / 3)} characters.
     * For ASCII, 200 and 300 characters become 304 and 440, respectively. A {@code varchar(500)}
     * holds up to 347 plaintext bytes, so the service also checks the passphrase's byte length.
     */
    record AddExchangeRequest(
        @NotNull ExchangeType type,
        @Size(max = 200) String apiKey,
        @Size(max = 300) String apiSecret,
        @Size(max = 200) String apiPassphrase,
        @Size(max = 20) String region
    ) {
        @Override
        public String toString() {
            return "AddExchangeRequest[type=" + type + ", credentials=redacted]";
        }
    }
}
