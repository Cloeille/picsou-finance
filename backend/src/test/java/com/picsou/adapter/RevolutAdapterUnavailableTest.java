package com.picsou.adapter;

import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.service.sync.SyncProgressService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RevolutAdapterUnavailableTest {

    @Test
    void sync_mapsAnUnreachableSidecarToNoResponse() throws IOException {
        RevolutAdapter adapter = new RevolutAdapter(
            new SidecarWebClientFactory(""),
            "http://127.0.0.1:" + closedPort(),
            mock(SyncProgressService.class));

        assertThatThrownBy(() -> adapter.sync("+336****0000", "123456", 5L))
            .isInstanceOf(SyncException.class)
            .hasMessage("No response from the Revolut service. Please try again later.");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
