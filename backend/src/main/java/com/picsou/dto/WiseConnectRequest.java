package com.picsou.dto;

import com.picsou.port.WisePort;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WiseConnectRequest(
    @NotBlank @Size(max = WisePort.MAX_TOKEN_CHARS) String token
) {}
