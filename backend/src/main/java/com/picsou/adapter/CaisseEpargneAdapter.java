package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.CaisseEpargnePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Caisse d'Epargne sidecar client. Everything but the {@code /initiate} body is the BPCE contract
 * shared in {@link BpceSidecarAdapter}: single-shot login calls, no retry, nothing logged.
 */
@Component
public class CaisseEpargneAdapter extends BpceSidecarAdapter implements CaisseEpargnePort {
    @Autowired
    public CaisseEpargneAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(client(clients, SidecarBank.CAISSE_EPARGNE), objectMapper, DEFAULT_CHECK_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    CaisseEpargneAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_CHECK_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    CaisseEpargneAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout
    ) {
        this(client, objectMapper, checkTimeout, accountsTimeout, INITIATE_TIMEOUT, COMPLETE_TIMEOUT);
    }

    CaisseEpargneAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration completeTimeout
    ) {
        this(client, objectMapper, checkTimeout, accountsTimeout, initiateTimeout, completeTimeout, KEYPAD_TIMEOUT);
    }

    CaisseEpargneAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration completeTimeout,
        Duration keypadTimeout
    ) {
        super(SidecarBank.CAISSE_EPARGNE, client, objectMapper, checkTimeout, accountsTimeout, initiateTimeout, completeTimeout,
            keypadTimeout);
    }

    @Override
    public InitiateResult initiateAuth(String customerId) {
        return initiate(new InitiateBody(customerId));
    }

    /** The identifier never leaves {@code toString}, so a logged DTO cannot leak it. */
    private record InitiateBody(String customerId) {
        @Override
        public String toString() {
            return "InitiateBody[customerId=***]";
        }
    }
}
