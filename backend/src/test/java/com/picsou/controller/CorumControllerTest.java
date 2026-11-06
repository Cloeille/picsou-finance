package com.picsou.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.service.CorumSyncService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorumControllerTest {
    private static final String PASSWORD = "s3cret-Pa55";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Mock CorumSyncService service;
    @Mock UserContext userContext;

    @Test
    void rememberDefaultsToFalseWhenTheFormDoesNotSendIt() throws Exception {
        CorumController.AuthenticateRequest request = json.readValue(
            "{\"login\":\"client\",\"password\":\"" + PASSWORD + "\"}", CorumController.AuthenticateRequest.class);

        assertThat(request.remember()).isFalse();
        assertThat(request.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void theLoginPassesRememberToTheServiceAndAnswersWithoutThePassword() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        CorumSyncService.SessionStatusResponse status = new CorumSyncService.SessionStatusResponse(
            true, SidecarSyncStatus.QUEUED, null, null, null, 0, true);
        when(service.authenticate("client", PASSWORD, true, 7L)).thenReturn(status);
        CorumController controller = new CorumController(
            service, userContext, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        CorumController.AuthenticateRequest request = json.readValue(
            "{\"login\":\"client\",\"password\":\"" + PASSWORD + "\",\"remember\":true}",
            CorumController.AuthenticateRequest.class);

        var response = controller.authenticate(request, new MockHttpServletRequest());

        verify(service).authenticate("client", PASSWORD, true, 7L);
        String body = json.writeValueAsString(response.getBody());
        assertThat(body).contains("\"credentialsRemembered\":true").doesNotContain(PASSWORD, "password");
    }
}
