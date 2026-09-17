package com.picsou.controller;

import com.picsou.dto.MemberProfileRequest;
import com.picsou.dto.MemberProfileResponse;
import com.picsou.model.HouseholdStatus;
import com.picsou.model.RiskProfile;
import com.picsou.service.MemberProfileService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeProfileControllerTest {

    @Mock MemberProfileService profileService;
    @Mock UserContext userContext;

    @InjectMocks MeProfileController controller;

    private static MemberProfileRequest request() {
        return new MemberProfileRequest(LocalDate.of(1990, 6, 1), new BigDecimal("30"),
            HouseholdStatus.SINGLE, BigDecimal.ONE, (short) 0, new BigDecimal("48000"),
            new BigDecimal("2750"), new BigDecimal("7.3"),
            new BigDecimal("800"), (short) 64, RiskProfile.BALANCED);
    }

    private static MemberProfileResponse response() {
        return new MemberProfileResponse(LocalDate.of(1990, 6, 1), 36, new BigDecimal("30"),
            HouseholdStatus.SINGLE, BigDecimal.ONE, (short) 0, new BigDecimal("48000"),
            new BigDecimal("2750"), new BigDecimal("7.3"), new BigDecimal("2549.25"),
            new BigDecimal("800"), (short) 64, RiskProfile.BALANCED);
    }

    @Test
    void getScopesToTheCurrentMember() {
        // The scoping contract at this layer: the id comes from the security context, never
        // from the request.
        when(userContext.currentMemberId()).thenReturn(42L);
        when(profileService.get(42L)).thenReturn(response());

        assertThat(controller.get().age()).isEqualTo(36);
        verify(profileService).get(42L);
    }

    @Test
    void replaceScopesToTheCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(42L);
        when(profileService.replace(eq(42L), any())).thenReturn(response());

        MemberProfileRequest body = request();
        controller.replace(body);

        verify(profileService).replace(42L, body);
    }
}
