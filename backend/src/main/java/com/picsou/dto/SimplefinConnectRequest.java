package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SimplefinConnectRequest(
    @NotBlank @Size(max = 4096) String token
) {}
