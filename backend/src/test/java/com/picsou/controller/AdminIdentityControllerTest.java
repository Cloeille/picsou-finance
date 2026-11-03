package com.picsou.controller;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.UserIdentityRepository;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminIdentityControllerTest {

    @Mock AppUserRepository userRepository;
    @Mock UserIdentityRepository identityRepository;
    @Mock PersistentSessionService persistentSessionService;
    @Mock SetupAuditService auditService;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    AdminIdentityController controller;
    AppUser admin;

    @BeforeEach
    void setUp() {
        controller = new AdminIdentityController(
            userRepository, identityRepository, persistentSessionService, auditService);
        admin = AppUser.builder().id(1L).username("root").role(UserRole.ADMIN).activated(true).build();
    }

    @Test
    void unlinkAll_deletesIdentitiesBumpsTvRevokesSessionsAndAudits_withoutAProof() {
        AppUser target = AppUser.builder().id(7L).username("alice").role(UserRole.MEMBER).tokenVersion(3L).build();
        when(userRepository.findByMemberId(42L)).thenReturn(Optional.of(target));

        ResponseEntity<Void> res = controller.unlinkAll(admin, 42L, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(target.getTokenVersion()).isEqualTo(4L);
        InOrder order = inOrder(identityRepository, userRepository, persistentSessionService);
        order.verify(identityRepository).deleteByUserId(7L);
        order.verify(userRepository).save(target);
        order.verify(persistentSessionService).revokeAllForUser(7L);
        verify(auditService).record(eq("admin.sso.identities-unlinked"), eq("root"), eq(request), any());
    }

    @Test
    void unlinkAll_unknownMember_is404() {
        when(userRepository.findByMemberId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.unlinkAll(admin, 99L, request))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(identityRepository, never()).deleteByUserId(anyLong());
    }

    @Test
    void unlinkAll_ownMember_is403AndChangesNothing() {
        when(userRepository.findByMemberId(10L)).thenReturn(Optional.of(admin));

        ResponseEntity<Void> res = controller.unlinkAll(admin, 10L, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(identityRepository, never()).deleteByUserId(anyLong());
        verify(persistentSessionService, never()).revokeAllForUser(anyLong());
        assertThat(admin.getTokenVersion()).isZero();
    }
}
