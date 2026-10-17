package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.service.CaisseEpargneSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CaisseEpargneControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock CaisseEpargneSyncService service;
    @Mock UserContext userContext;

    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        syncBuckets = new ConcurrentHashMap<>();
        CaisseEpargneController controller =
            new CaisseEpargneController(service, userContext, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), syncBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    private static CaisseEpargneSyncService.SessionStatusResponse statusOf(CaisseEpargneSyncStatus syncStatus) {
        return new CaisseEpargneSyncService.SessionStatusResponse(
            true, syncStatus, null, null, null,
            List.of(new CaisseEpargneSyncService.UnsupportedContract("9001", "7")));
    }

    @Test
    void syncQueuesTheSyncForTheCurrentMemberAndAnswers202() throws Exception {
        when(service.queueSync(MEMBER_ID)).thenReturn(statusOf(CaisseEpargneSyncStatus.QUEUED));

        mockMvc.perform(post("/api/caisse-epargne/sync").with(request -> {
                request.setRemoteAddr("127.0.0.1");
                return request;
            }))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"))
            .andExpect(jsonPath("$.unsupportedCount").value(1))
            .andExpect(jsonPath("$.unsupported[0].externalId").value("9001"))
            .andExpect(jsonPath("$.unsupported[0].familyCode").value("7"));

        verify(service).queueSync(MEMBER_ID);
    }

    @Test
    void syncIsRateLimitedLikeTheOtherSyncEndpoints() throws Exception {
        when(service.queueSync(MEMBER_ID)).thenReturn(statusOf(CaisseEpargneSyncStatus.QUEUED));
        var request = post("/api/caisse-epargne/sync").with(r -> {
            r.setRemoteAddr("127.0.0.1");
            return r;
        });

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(request).andExpect(status().isAccepted());
        }
        mockMvc.perform(request)
            .andExpect(status().isTooManyRequests());

        verify(service, times(10)).queueSync(MEMBER_ID);
        assertThat(syncBuckets).containsOnlyKeys("127.0.0.1");
    }

    @Test
    void theRateLimitIsPerClientIp() throws Exception {
        when(service.queueSync(anyLong())).thenReturn(statusOf(CaisseEpargneSyncStatus.QUEUED));

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/caisse-epargne/sync").with(r -> {
                r.setRemoteAddr("127.0.0.1");
                return r;
            })).andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/api/caisse-epargne/sync").with(r -> {
            r.setRemoteAddr("127.0.0.2");
            return r;
        })).andExpect(status().isAccepted());
    }

    @Test
    void aMissingSessionReachesTheClientAsATranslatableCode() throws Exception {
        when(service.queueSync(MEMBER_ID)).thenThrow(new SyncException(
            "No active Caisse d'Epargne session. Please reconnect.", null,
            CaisseEpargneErrorCode.SESSION_EXPIRED.name()));

        mockMvc.perform(post("/api/caisse-epargne/sync").with(r -> {
                r.setRemoteAddr("127.0.0.1");
                return r;
            }))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("SESSION_EXPIRED")));
    }

    @Test
    void statusReturnsTheStoredState() throws Exception {
        when(service.getStatus(MEMBER_ID)).thenReturn(statusOf(CaisseEpargneSyncStatus.SUCCESS));

        mockMvc.perform(get("/api/caisse-epargne/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.syncStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.unsupportedCount").value(1));
    }

    @Test
    void deletingTheSessionDoesNotClaimTheBankSessionWasRevoked() throws Exception {
        when(service.clearSession(MEMBER_ID)).thenReturn(true);

        String body = mockMvc.perform(delete("/api/caisse-epargne/session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.removed").value(true))
            .andExpect(jsonPath("$.bankSessionRevoked").value(false))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("may remain active")))
            .andReturn().getResponse().getContentAsString();

        assertThat(body.toLowerCase()).doesNotContain("\"bankSessionRevoked\":true".toLowerCase());
        assertThat(body.toLowerCase()).doesNotContain("logged out").doesNotContain("disconnected from");
        verify(service).clearSession(MEMBER_ID);
    }

    @Test
    void deletingWhenNothingIsStoredIsStillAnAnswerNotAnError() throws Exception {
        when(service.clearSession(MEMBER_ID)).thenReturn(false);

        mockMvc.perform(delete("/api/caisse-epargne/session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.removed").value(false))
            .andExpect(jsonPath("$.bankSessionRevoked").value(false));
    }

    @Test
    void onlyTheThreeAuthEndpointsCollectAnything() throws Exception {
        // Guessable alternatives stay closed: only /auth/initiate, /auth/keypad and /auth/complete exist.
        for (String path : List.of("/auth", "/login", "/session", "/auth/password", "/auth/store")) {
            mockMvc.perform(post("/api/caisse-epargne" + path)).andExpect(status().is4xxClientError());
        }
        verify(service, never()).queueSync(anyLong());
    }

    @Test
    void theControllerOnlyDeclaresAuthSyncStatusAndClear() {
        var names = new TreeSet<String>();
        for (Method method : CaisseEpargneController.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                names.add(method.getName());
            }
        }
        assertThat(names).containsExactly("clear", "complete", "initiate", "keypad", "status", "sync");
    }
}
