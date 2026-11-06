package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarTestSupport;
import com.picsou.exception.SyncException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DegiroAdapterUnavailableTest {

    private static final String NO_RESPONSE = "No response from the DEGIRO service. Please try again later.";

    @Test
    void initiateAuth_mapsAnUnreachableSidecarToNoResponse() throws IOException {
        DegiroAdapter adapter = adapterOnClosedPort();

        assertThatThrownBy(() -> adapter.initiateAuth("user", "password"))
            .isInstanceOf(SyncException.class)
            .hasMessage(NO_RESPONSE);
    }

    @Test
    void completeAuth_mapsAnUnreachableSidecarToNoResponse() throws IOException {
        DegiroAdapter adapter = adapterOnClosedPort();

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOf(SyncException.class)
            .hasMessage(NO_RESPONSE);
    }

    @Test
    void fetchPortfolio_mapsAnUnreachableSidecarToNoResponse() throws IOException {
        DegiroAdapter adapter = adapterOnClosedPort();

        assertThatThrownBy(() -> adapter.fetchPortfolio("blob"))
            .isInstanceOf(SyncException.class)
            .hasMessage(NO_RESPONSE);
    }

    private static DegiroAdapter adapterOnClosedPort() throws IOException {
        return new DegiroAdapter(
            SidecarTestSupport.factory("degiro-auth", "http://127.0.0.1:" + closedPort(), ""), new ObjectMapper());
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
