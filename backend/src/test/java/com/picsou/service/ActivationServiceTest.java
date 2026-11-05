package com.picsou.service;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationServiceTest {

    @Mock AppUserRepository userRepository;
    @Mock PersistentSessionService persistentSessionService;
    @Mock SetupAuditService auditService;

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private ActivationService service() {
        return new ActivationService(userRepository, persistentSessionService, auditService);
    }

    private static AppUser invited(UserRole role) {
        return AppUser.builder().id(11L).username("bob").role(role).passwordHash("old-hash").activated(false)
            .acknowledgedWarning(false).tokenVersion(5L).activationToken("tok")
            .activationTokenExpires(Instant.parse("2026-11-05T00:00:00Z")).build();
    }

    @Test
    void completeSetsTheHashActivatesAndRevokesEverySession() {
        AppUser user = invited(UserRole.MEMBER);

        service().complete(user, "new-hash", request);

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getActivationToken()).isNull();
        assertThat(user.getActivationTokenExpires()).isNull();
        assertThat(user.isActivated()).isTrue();
        assertThat(user.isAcknowledgedWarning()).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(6L);
        InOrder order = inOrder(userRepository, persistentSessionService);
        order.verify(userRepository).save(user);
        order.verify(persistentSessionService).revokeAllForUser(11L);
        verifyNoInteractions(auditService);
    }

    @Test
    void completeWithABlankHashLeavesThePasswordlessAccount() {
        AppUser user = invited(UserRole.MEMBER);

        service().complete(user, "", request);

        assertThat(user.getPasswordHash()).isEmpty();
    }

    @Test
    void completeInvitationCompletesTheRowFoundUnderLock() {
        AppUser user = invited(UserRole.MEMBER);
        when(userRepository.findByActivationTokenForUpdate("tok")).thenReturn(Optional.of(user));

        assertThat(service().completeInvitation("tok", "new-hash", request)).isTrue();

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getActivationToken()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void completeInvitationReportsATokenAlreadyUsedAndWritesNothing() {
        when(userRepository.findByActivationTokenForUpdate("tok")).thenReturn(Optional.empty());

        assertThat(service().completeInvitation("tok", "new-hash", request)).isFalse();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(persistentSessionService, auditService);
    }

    @Test
    void completeAuditsAnAdmin() {
        AppUser user = invited(UserRole.ADMIN);

        service().complete(user, "h", request);

        verify(auditService).record("admin.recovery.completed", "bob", request, null);
    }
}
