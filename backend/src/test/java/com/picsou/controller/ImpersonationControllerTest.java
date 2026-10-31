package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.SofidySyncStatus;
import com.picsou.model.UserRole;
import com.picsou.repository.AccountOwnershipRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.AccountAccessResolver;
import com.picsou.service.AccountConnectionService;
import com.picsou.service.AccountOwnershipService;
import com.picsou.service.AccountService;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.HoldingClassificationService;
import com.picsou.service.ManualTransactionService;
import com.picsou.service.PropertyValuationService;
import com.picsou.service.RealizedPnlService;
import com.picsou.service.ScpiPositionService;
import com.picsou.service.SofidySyncService;
import com.picsou.service.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP boundary has to apply {@link UserContext} itself. A service test
 * that receives a member id cannot see an admin override, an activated member,
 * or a co-owner stopped before the write.
 */
@ExtendWith(MockitoExtension.class)
class ImpersonationControllerTest {

    private static final long ADMIN_MEMBER_ID = 1L;

    @Mock AppUserRepository userRepository;
    @Mock SofidySyncService sofidyService;
    @Mock AccountService accountService;
    @Mock HoldingClassificationService holdingClassificationService;
    @Mock ManualTransactionService manualTransactionService;
    @Mock RealizedPnlService realizedPnlService;
    @Mock CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock PropertyValuationService propertyValuationService;
    @Mock AccountOwnershipService ownershipService;
    @Mock AccountConnectionService accountConnectionService;
    @Mock ScpiPositionService scpiPositionService;
    @Mock AccountOwnershipRepository ownershipRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountAccessResolver accessResolver;

    private UserContext userContext;
    private MockMvc sofidy;
    private MockMvc accounts;

    @BeforeEach
    void setUp() {
        userContext = new UserContext(userRepository);
        sofidy = mvc(new SofidyController(
            sofidyService, userContext, new ConcurrentHashMap<>(), new ConcurrentHashMap<>()
        ));
        accounts = mvc(new AccountController(
            accountService, userContext, manualTransactionService, realizedPnlService,
            cryptoExchangeSyncService, propertyValuationService, ownershipService,
            accountConnectionService, holdingClassificationService, scpiPositionService
        ));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void activatedMemberCannotBeImpersonatedOnSofidyComplete() throws Exception {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        when(userRepository.findByMemberId(2L))
            .thenReturn(Optional.of(member(2L, UserRole.MEMBER, true)));

        sofidy.perform(post("/api/sofidy/auth/complete")
                .param("memberId", "2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"proc-a\",\"code\":\"123456\"}"))
            .andExpect(status().isForbidden());

        verify(sofidyService, never()).completeAuth(any(), any(), any());
    }

    @Test
    void managedMemberOverrideIsTheMemberPassedToSofidy() throws Exception {
        authenticate(member(ADMIN_MEMBER_ID, UserRole.ADMIN, true));
        when(userRepository.findByMemberId(3L))
            .thenReturn(Optional.of(member(3L, UserRole.MEMBER, false)));
        when(sofidyService.completeAuth("proc-a", "123456", 3L))
            .thenReturn(new SofidySyncService.SessionStatusResponse(
                false, SofidySyncStatus.IDLE, null, null, null));

        sofidy.perform(post("/api/sofidy/auth/complete")
                .param("memberId", "3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"proc-a\",\"code\":\"123456\"}"))
            .andExpect(status().isOk());

        verify(sofidyService).completeAuth("proc-a", "123456", 3L);
    }

    @Test
    void nonAdminMemberIdParamDoesNotChangeTheSofidyMember() throws Exception {
        authenticate(member(5L, UserRole.MEMBER, true));
        when(sofidyService.completeAuth("proc-a", "123456", 5L))
            .thenReturn(new SofidySyncService.SessionStatusResponse(
                false, SofidySyncStatus.IDLE, null, null, null));

        sofidy.perform(post("/api/sofidy/auth/complete")
                .param("memberId", "3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"proc-a\",\"code\":\"123456\"}"))
            .andExpect(status().isOk());

        verify(sofidyService).completeAuth("proc-a", "123456", 5L);
        verify(userRepository, never()).findByMemberId(any());
    }

    @Test
    void coOwnerCanReadOwnershipButCannotReplaceIt() throws Exception {
        FamilyMember alice = FamilyMember.builder().id(1L).displayName("Alice").build();
        FamilyMember bob = FamilyMember.builder().id(2L).displayName("Bob").build();
        Account house = Account.builder()
            .id(10L).name("SCPI").type(AccountType.SCPI).currency("EUR")
            .currentBalance(new BigDecimal("1000")).color("#a855f7").member(alice)
            .build();
        AccountOwnershipService realOwnership = new AccountOwnershipService(
            ownershipRepository, memberRepository, accessResolver);
        MockMvc owned = mvc(new AccountController(
            accountService, userContext, manualTransactionService, realizedPnlService,
            cryptoExchangeSyncService, propertyValuationService, realOwnership,
            accountConnectionService, holdingClassificationService, scpiPositionService
        ));
        authenticate(AppUser.builder()
            .id(2L).username("bob").role(UserRole.MEMBER).activated(true).member(bob).build());
        when(accessResolver.requireReadable(10L, 2L)).thenReturn(house);
        when(accessResolver.requireOwner(10L, 2L))
            .thenThrow(new AccessDeniedException("Only the owning member can modify this account"));
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of());

        owned.perform(get("/api/accounts/10/ownership"))
            .andExpect(status().isOk());
        owned.perform(put("/api/accounts/10/ownership")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shares\":[{\"memberId\":1,\"sharePercent\":60},{\"memberId\":2,\"sharePercent\":40}]}"))
            .andExpect(status().isForbidden());

        verify(ownershipRepository, never()).deleteAllForAccount(eq(10L));
    }

    private void authenticate(AppUser user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null));
    }

    private AppUser member(long memberId, UserRole role, boolean activated) {
        FamilyMember familyMember = FamilyMember.builder().id(memberId).managed(role != UserRole.ADMIN).build();
        return AppUser.builder()
            .id(memberId)
            .username("user" + memberId)
            .role(role)
            .activated(activated)
            .member(familyMember)
            .build();
    }

    private static MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain
                ) throws java.io.IOException, jakarta.servlet.ServletException {
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                }
            })
            .build();
    }
}
