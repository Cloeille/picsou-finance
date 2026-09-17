package com.picsou.controller;

import com.picsou.exception.SyncException;
import com.picsou.model.FortuneoSyncStatus;
import com.picsou.port.FortuneoErrorCode;
import com.picsou.service.FortuneoSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FortuneoControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock FortuneoSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private FortuneoController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        controller = new FortuneoController(service, userContext, authBuckets);
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void initiateScopesAuthenticationToTheCurrentMember() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var expected = new FortuneoSyncService.AuthInitResponse("process", true, "OTP");
        when(service.initiateAuth("login", "password", MEMBER_ID)).thenReturn(expected);

        var response = controller.initiate(
            new FortuneoController.InitiateRequest("login", "password"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(service).initiateAuth("login", "password", MEMBER_ID);
    }

    @Test
    void authenticationRateLimitUsesTheTrustedProxyClientIp() {
        var proxiedRequest = new MockHttpServletRequest();
        proxiedRequest.setRemoteAddr("172.18.0.2");
        proxiedRequest.addHeader("X-Forwarded-For", "1.2.3.4");
        proxiedRequest.addHeader("X-Real-IP", "203.0.113.9");
        when(service.initiateAuth("login", "password", MEMBER_ID))
            .thenReturn(new FortuneoSyncService.AuthInitResponse("process", true, "OTP"));

        controller.initiate(new FortuneoController.InitiateRequest("login", "password"), proxiedRequest);

        assertThat(authBuckets).containsOnlyKeys("203.0.113.9");
    }

    @Test
    void completeAuthScopesOtpToTheCurrentMember() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var queued = sessionStatus(FortuneoSyncStatus.QUEUED);
        when(service.completeAuth("process-123", "123456", MEMBER_ID)).thenReturn(queued);

        var response = controller.complete(
            new FortuneoController.CompleteRequest("process-123", "123456"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(queued);
        verify(service).completeAuth("process-123", "123456", MEMBER_ID);
    }

    @Test
    void completeAuthPreservesTheStableInvalidOtpCode() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.completeAuth("process-123", "000000", MEMBER_ID)).thenThrow(
            new SyncException(
                "Fortuneo rejected the verification code",
                null,
                FortuneoErrorCode.INVALID_OTP.name()
            )
        );

        assertThatThrownBy(() -> controller.complete(
                new FortuneoController.CompleteRequest("process-123", "000000"),
                request
            ))
            .isInstanceOf(SyncException.class)
            .satisfies(thrown -> assertThat(((SyncException) thrown).getCode())
                .isEqualTo("INVALID_OTP"));
    }

    @Test
    void authenticationPayloadValidationRejectsOversizedLoginAndMalformedOtp() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var invalidLogin = validator.validate(
                new FortuneoController.InitiateRequest("x".repeat(101), "secret")
            );
            var invalidOtp = validator.validate(
                new FortuneoController.CompleteRequest("process", "12ab")
            );

            assertThat(invalidLogin)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("login");
            assertThat(invalidOtp)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("code");
        }
        verify(service, times(0)).initiateAuth(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(service, times(0)).completeAuth(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void sixthAuthenticationAttemptFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.initiateAuth("login", "password", MEMBER_ID)).thenReturn(
            new FortuneoSyncService.AuthInitResponse("process", true, "OTP")
        );

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(controller.initiate(
                new FortuneoController.InitiateRequest("login", "password"),
                request
            ).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.initiate(
            new FortuneoController.InitiateRequest("login", "password"),
            request
        ).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5)).initiateAuth("login", "password", MEMBER_ID);
    }

    @Test
    void syncReturnsAcceptedAndTheObservableQueueStatus() {
        var queued = sessionStatus(FortuneoSyncStatus.QUEUED);
        when(service.queueSync(MEMBER_ID)).thenReturn(queued);

        var response = controller.sync();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(queued);
        verify(service).queueSync(MEMBER_ID);
    }

    @Test
    void statusAndClearUseOnlyTheCurrentMember() {
        var success = sessionStatus(FortuneoSyncStatus.SUCCESS);
        when(service.getStatus(MEMBER_ID)).thenReturn(success);

        assertThat(controller.status()).isSameAs(success);
        assertThat(controller.clear().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
    }

    private FortuneoSyncService.SessionStatusResponse sessionStatus(FortuneoSyncStatus syncStatus) {
        return new FortuneoSyncService.SessionStatusResponse(
            true, null, syncStatus, null, null, null
        );
    }
}
