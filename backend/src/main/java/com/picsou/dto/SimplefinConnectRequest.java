package com.picsou.dto;

import com.picsou.port.SimplefinPort;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SimplefinConnectRequest(
    @NotBlank @Size(max = SimplefinPort.MAX_SETUP_TOKEN_CHARS) String token
) {}
