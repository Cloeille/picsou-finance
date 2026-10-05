package com.picsou.dto;

import com.picsou.model.AccountType;
import com.picsou.validation.ValidCurrency;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AccountRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull AccountType type,
    @Size(max = 100) String provider,
    @NotBlank @Size(max = 10) @ValidCurrency String currency,
    @DecimalMin("0") BigDecimal currentBalance,
    boolean isManual,
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex color") String color,
    @Size(max = 20) String ticker,
    // Which bundled frontend asset the account shows ("blockchain", "ledger"...). Validated as a
    // slug rather than against a fixed list: the assets live in the frontend, so pinning the
    // allowed values here would mean a backend release every time one is added. An unknown key
    // simply resolves to no logo client-side. Null clears the choice.
    @Pattern(regexp = "^[a-z0-9-]{1,32}$", message = "Logo key must be a lowercase slug") String logoKey,
    // The bank the user picked in the account form, as the institution catalog's own round-trip
    // token ("BankName::FR::personal"). Never stored: it is consumed once to look the bank's logo
    // up server-side, which is the only way a manual account gets one. An opaque id rather than
    // the logo URL itself, because nothing between a client-supplied URL and the Accounts page
    // <img src> would validate its scheme or host. Null falls back to matching on `provider`.
    @Size(max = 200) String institutionId,
    // When the wrapper was opened, for the types whose tax treatment turns on its age (a PEA's
    // fifth anniversary, an assurance-vie's eighth). Optional, and null means "leave it alone"
    // rather than "clear it" -- the MCP tools have no such parameter and would otherwise erase
    // the date on any unrelated update. See AccountService.update.
    @PastOrPresent LocalDate openedAt
) {}
