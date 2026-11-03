package com.picsou.service.sso;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.config.SsoProperties;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.SetupAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsoRoleSyncServiceTest {

    private static final String ADMIN_GROUP = "picsou-admin";

    @Mock private AppUserRepository appUserRepository;
    @Mock private SetupAuditService setupAuditService;
    @Mock private SsoProperties ssoProperties;
    @Mock private HttpServletRequest request;

    private SsoRoleSyncService service;
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = new SsoRoleSyncService(appUserRepository, setupAuditService, ssoProperties);
        lenient().when(ssoProperties.adminGroupManaged()).thenReturn(true);
        lenient().when(ssoProperties.adminGroup()).thenReturn(ADMIN_GROUP);
        serviceLogger = (Logger) LoggerFactory.getLogger(SsoRoleSyncService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    private static AppUser user(long id, String username, UserRole role) {
        return AppUser.builder().id(id).username(username).passwordHash("x").role(role).build();
    }

    private List<ILoggingEvent> events() {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    @Test
    void notManaged_returnsCurrentRoleAndDoesNothing() {
        when(ssoProperties.adminGroupManaged()).thenReturn(false);
        AppUser caller = user(1, "alice", UserRole.ADMIN);

        UserRole result = service.sync(caller, Optional.of(Set.of()), request);

        assertThat(result).isEqualTo(UserRole.ADMIN);
        verifyNoInteractions(appUserRepository, setupAuditService);
    }

    @Test
    void inGroup_alreadyAdmin_noChange() {
        AppUser caller = user(1, "alice", UserRole.ADMIN);

        UserRole result = service.sync(caller, Optional.of(Set.of(ADMIN_GROUP)), request);

        assertThat(result).isEqualTo(UserRole.ADMIN);
        verifyNoInteractions(appUserRepository, setupAuditService);
    }

    @Test
    void inGroup_member_isPromotedOnTheManagedRow() {
        AppUser caller = user(1, "alice", UserRole.MEMBER);
        AppUser managed = user(1, "alice", UserRole.MEMBER);
        when(appUserRepository.findById(1L)).thenReturn(Optional.of(managed));

        UserRole result = service.sync(caller, Optional.of(Set.of(ADMIN_GROUP)), request);

        assertThat(result).isEqualTo(UserRole.ADMIN);
        assertThat(managed.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(caller.getRole()).isEqualTo(UserRole.ADMIN);
        verify(appUserRepository).save(same(managed));
        verify(appUserRepository, never()).save(same(caller));
        verify(setupAuditService).record("sso.role.promoted", "alice", request, null);
    }

    @Test
    void notInGroup_member_noChange() {
        AppUser caller = user(1, "alice", UserRole.MEMBER);

        UserRole result = service.sync(caller, Optional.of(Set.of("other")), request);

        assertThat(result).isEqualTo(UserRole.MEMBER);
        verifyNoInteractions(appUserRepository, setupAuditService);
    }

    @Test
    void notInGroup_admin_withAnotherAdmin_isDemotedAfterLocking() {
        AppUser caller = user(1, "alice", UserRole.ADMIN);
        AppUser managed = user(1, "alice", UserRole.ADMIN);
        AppUser other = user(2, "bob", UserRole.ADMIN);
        when(appUserRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(managed, other));

        UserRole result = service.sync(caller, Optional.of(Set.of("other")), request);

        assertThat(result).isEqualTo(UserRole.MEMBER);
        assertThat(managed.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(caller.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(other.getRole()).isEqualTo(UserRole.ADMIN);
        InOrder order = inOrder(appUserRepository);
        order.verify(appUserRepository).findAllByRoleForUpdate(UserRole.ADMIN);
        order.verify(appUserRepository).save(same(managed));
        verify(appUserRepository, never()).save(same(caller));
        verify(setupAuditService).record("sso.role.demoted", "alice", request, null);
    }

    @Test
    void notInGroup_onlyAdmin_isKeptAndAudited() {
        AppUser caller = user(1, "alice", UserRole.ADMIN);
        AppUser managed = user(1, "alice", UserRole.ADMIN);
        when(appUserRepository.findAllByRoleForUpdate(UserRole.ADMIN)).thenReturn(List.of(managed));

        UserRole result = service.sync(caller, Optional.of(Set.of()), request);

        assertThat(result).isEqualTo(UserRole.ADMIN);
        assertThat(managed.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(caller.getRole()).isEqualTo(UserRole.ADMIN);
        verify(appUserRepository, never()).save(any());
        verify(setupAuditService).record("sso.role.demotion-skipped-last-admin", "alice", request, null);
        assertThat(events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                .isEqualTo("sso.role.demotion-skipped-last-admin user=alice");
        });
    }

    @Test
    void missingClaim_warnsAndCountsAsEmptySet() {
        AppUser caller = user(1, "alice", UserRole.MEMBER);

        UserRole result = service.sync(caller, Optional.empty(), request);

        assertThat(result).isEqualTo(UserRole.MEMBER);
        assertThat(events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("sso.groups-claim-missing user=alice");
        });
        verifyNoInteractions(setupAuditService);
    }
}
