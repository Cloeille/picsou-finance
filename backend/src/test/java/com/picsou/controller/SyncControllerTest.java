package com.picsou.controller;

import com.picsou.service.SyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SyncControllerTest {

    @Mock SyncService syncService;
    @Mock UserContext userContext;
    @Mock HttpServletRequest httpRequest;

    private SyncController controller(Map<String, Bucket> syncBuckets) {
        return new SyncController(syncService, userContext, syncBuckets);
    }

    @Test
    void listCountries_returnsServiceResult() {
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(syncService.listCountries()).thenReturn(List.of("FR", "DE", "EE"));

        ResponseEntity<?> response = controller(new ConcurrentHashMap<>()).listCountries(httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(List.of("FR", "DE", "EE"));
    }

    @Test
    void listCountries_rateLimitExceeded_returns429_withoutCallingService() {
        when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.1");
        Map<String, Bucket> buckets = new ConcurrentHashMap<>();
        // Pre-seed an already-exhausted, single-token bucket for this IP's "countries" key.
        buckets.put("10.0.0.1:countries", exhaustedOneTokenBucket());
        SyncController ctrl = controller(buckets);

        ResponseEntity<?> response = ctrl.listCountries(httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) response.getBody()).getDetail()).contains("Too many sync requests");
        verifyNoInteractions(syncService);
    }

    @Test
    void listCountries_usesItsOwnBucket_separateFromInitiate() {
        // A passive picker load shouldn't be blocked by an exhausted "initiate" budget —
        // each endpoint gets its own bucket keyed by ip + endpoint name.
        when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.2");
        when(syncService.listCountries()).thenReturn(List.of("FR"));
        Map<String, Bucket> buckets = new ConcurrentHashMap<>();
        buckets.put("10.0.0.2:initiate", exhaustedOneTokenBucket());
        SyncController ctrl = controller(buckets);

        ResponseEntity<?> response = ctrl.listCountries(httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(List.of("FR"));
    }

    @Test
    void complete_isAPostCarryingCodeAndStateInTheBody() throws Exception {
        when(userContext.currentMemberId()).thenReturn(4L);
        when(syncService.completeConnection("auth-code", "nonce-1", 4L)).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(new ConcurrentHashMap<>())).build();

        mvc.perform(post("/api/sync/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"auth-code\",\"state\":\"nonce-1\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));

        verify(syncService).completeConnection("auth-code", "nonce-1", 4L);
    }

    @Test
    void complete_noLongerAnswersGet() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(new ConcurrentHashMap<>())).build();

        mvc.perform(get("/api/sync/complete").param("code", "auth-code"))
            .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(syncService);
    }

    @Test
    void complete_withoutCode_isRejected() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(new ConcurrentHashMap<>())).build();

        mvc.perform(post("/api/sync/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"nonce-1\"}"))
            .andExpect(status().is4xxClientError());

        verifyNoInteractions(syncService);
    }

    private static Bucket exhaustedOneTokenBucket() {
        Bucket bucket = Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(1).refillIntervally(1, Duration.ofMinutes(1)).build())
            .build();
        bucket.tryConsume(1);
        return bucket;
    }
}
