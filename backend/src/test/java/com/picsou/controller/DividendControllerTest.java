package com.picsou.controller;

import com.picsou.service.DividendForecastService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DividendControllerTest {

    @Mock DividendForecastService forecastService;
    @Mock UserContext userContext;

    private DividendController controller;

    @BeforeEach
    void setUp() {
        controller = new DividendController(forecastService, userContext);
        when(userContext.currentMemberId()).thenReturn(7L);
        when(forecastService.upcoming(any(), any(), any(Integer.class), nullable(Long.class))).thenReturn(List.of());
    }

    @Test
    void upcoming_clampsANonPositiveHorizonToOne() {
        controller.upcoming(0, null);
        controller.upcoming(-4, null);

        verify(forecastService, times(2)).upcoming(eq(7L), any(LocalDate.class), eq(1), isNull());
    }

    @Test
    void upcoming_clampsAnOverlongHorizonTo366() {
        controller.upcoming(900, null);

        verify(forecastService).upcoming(eq(7L), any(LocalDate.class), eq(366), isNull());
    }

    @Test
    void upcoming_passesAHorizonInsideTheRangeUnchanged() {
        controller.upcoming(60, null);
        controller.upcoming(1, null);
        controller.upcoming(366, null);

        verify(forecastService).upcoming(eq(7L), any(LocalDate.class), eq(60), isNull());
        verify(forecastService).upcoming(eq(7L), any(LocalDate.class), eq(1), isNull());
        verify(forecastService).upcoming(eq(7L), any(LocalDate.class), eq(366), isNull());
    }

    @Test
    void upcoming_passesTheAccountIdThrough() {
        controller.upcoming(90, 12L);

        verify(forecastService).upcoming(eq(7L), any(LocalDate.class), eq(90), eq(12L));
    }
}
