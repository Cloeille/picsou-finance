package com.picsou.service;

import com.picsou.config.AccessKeyAuthentication;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.support.TestEntities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Privacy boundary: an admin may impersonate (?memberId=X) only members who have
 * NOT activated their own login. Once a member has set their own password
 * (activated = true), the override is refused with 403.
 */
@ExtendWith(MockitoExtension.class)
class UserContextTest {

    @Mock AppUserRepository userRepository;

    UserContext userContext;
    MockHttpServletRequest request;

    static final long ADMIN_MEMBER_ID = 1L;

    @BeforeEach
    void setUp() {
        userContext = new UserContext(userRepository);
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void authenticate(AppUser user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null));
    }

    @Test
    void adminOverrideToActivatedMember_isForbidden() {
        authenticate(TestEntities.user(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "2");
        when(userRepository.findByMemberId(2L))
            .thenReturn(Optional.of(TestEntities.user(2L, UserRole.MEMBER, true)));

        assertThatThrownBy(() -> userContext.currentMemberId())
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminOverrideToManagedNotYetActivatedMember_isAllowed() {
        authenticate(TestEntities.user(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "3");
        when(userRepository.findByMemberId(3L))
            .thenReturn(Optional.of(TestEntities.user(3L, UserRole.MEMBER, false)));

        assertThat(userContext.currentMemberId()).isEqualTo(3L);
    }

    @Test
    void adminOverrideToManagedProfileWithNoLogin_isAllowed() {
        authenticate(TestEntities.user(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", "4");
        when(userRepository.findByMemberId(4L)).thenReturn(Optional.empty());

        assertThat(userContext.currentMemberId()).isEqualTo(4L);
    }

    @Test
    void adminOverrideToSelf_isAllowed_withoutRepoLookup() {
        authenticate(TestEntities.user(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        request.setParameter("memberId", String.valueOf(ADMIN_MEMBER_ID));

        assertThat(userContext.currentMemberId()).isEqualTo(ADMIN_MEMBER_ID);
    }

    @Test
    void nonAdminMemberIdParam_isIgnored() {
        authenticate(TestEntities.user(5L, UserRole.MEMBER, true));
        request.setParameter("memberId", "2");

        assertThat(userContext.currentMemberId()).isEqualTo(5L);
    }

    // ─── Property B: an access-key never impersonates, even if its owner is an admin ──

    @Test
    void accessKey_ignoresMemberIdOverride_evenWhenOwnerIsAdmin() {
        // Same principal a cookie-admin would use, but presented as an access-key.
        // For a cookie-admin, ?memberId=3 (a managed, not-yet-activated profile) is a
        // permitted override; for a key it must be silently refused so the key only ever
        // touches its own member's data — and the repo is never even consulted.
        AppUser adminOwner = TestEntities.user(ADMIN_MEMBER_ID, UserRole.ADMIN, true);
        SecurityContextHolder.getContext().setAuthentication(
            new AccessKeyAuthentication(adminOwner, List.of(), 99L));
        request.setParameter("memberId", "3");

        assertThat(userContext.currentMemberId()).isEqualTo(ADMIN_MEMBER_ID);
    }
}
