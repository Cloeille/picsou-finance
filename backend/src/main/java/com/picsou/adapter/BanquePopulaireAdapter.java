package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.BanquePopulairePort;
import com.picsou.port.BanquePopulaireRegion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Banque Populaire sidecar client. Everything but the {@code /initiate} body, which also names the
 * customer's regional bank, is the BPCE contract shared in {@link BpceSidecarAdapter}: single-shot
 * login calls, no retry, nothing logged.
 */
@Component
public class BanquePopulaireAdapter extends BpceSidecarAdapter implements BanquePopulairePort {
    @Autowired
    public BanquePopulaireAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(client(clients, SidecarBank.BANQUE_POPULAIRE), objectMapper, DEFAULT_CHECK_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    BanquePopulaireAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_CHECK_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    BanquePopulaireAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout
    ) {
        this(client, objectMapper, checkTimeout, accountsTimeout, INITIATE_TIMEOUT, COMPLETE_TIMEOUT);
    }

    BanquePopulaireAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration completeTimeout
    ) {
        this(client, objectMapper, checkTimeout, accountsTimeout, initiateTimeout, completeTimeout, KEYPAD_TIMEOUT);
    }

    BanquePopulaireAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration completeTimeout,
        Duration keypadTimeout
    ) {
        super(SidecarBank.BANQUE_POPULAIRE, client, objectMapper, checkTimeout, accountsTimeout, initiateTimeout, completeTimeout,
            keypadTimeout);
    }

    @Override
    public InitiateResult initiateAuth(String customerId, BanquePopulaireRegion region) {
        return initiate(new InitiateBody(customerId, region.name()));
    }

    /**
     * The identifier never leaves {@code toString}, so a logged DTO cannot leak it. The region is a
     * public list entry, not a secret.
     */
    private record InitiateBody(String customerId, String region) {
        @Override
        public String toString() {
            return "InitiateBody[customerId=***, region=" + region + "]";
        }
    }
}
