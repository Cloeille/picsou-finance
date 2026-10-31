package com.picsou.controller;

import com.picsou.dto.ActualBudgetImportDtos.Plan;
import com.picsou.dto.ActualBudgetImportDtos.Result;
import com.picsou.dto.ActualBudgetImportDtos.Warning;
import com.picsou.dto.ActualBudgetImportDtos.WarningReason;
import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.service.ActualBudgetImportService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ActualBudgetImportControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock ActualBudgetImportService service;
    @Mock UserContext userContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ActualBudgetImportController controller =
            new ActualBudgetImportController(service, userContext, new ConcurrentHashMap<String, Bucket>());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void anUnsupportedFileIsAProblemDetail400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "budget.csv", "text/csv", "a,b".getBytes());
        when(service.preview(any(), eq(MEMBER_ID)))
            .thenThrow(new IllegalArgumentException("Unsupported file: expected an Actual Budget export (.zip) or its db.sqlite"));

        mockMvc.perform(multipart("/api/actual/import/preview").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unsupported file: expected an Actual Budget export (.zip) or its db.sqlite"));
    }

    @Test
    void executeImportsForTheCurrentMemberAndAnswers201() throws Exception {
        when(service.executeImport(any(), eq(MEMBER_ID))).thenReturn(new Result(1, 0, 0, 2, 3, 0, 0, 0, List.of()));

        mockMvc.perform(post("/api/actual/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileToken\":\"t\",\"currency\":\"EUR\",\"accountMappings\":[],\"categoryMappings\":[]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transactionsImported").value(3));
    }

    @Test
    void planAnswersWhatTheImportWouldChange() throws Exception {
        when(service.planImport(any(), eq(MEMBER_ID))).thenReturn(
            new Plan(2, 1, 0, List.of(new Warning(WarningReason.KEPT_MISSING, 3)), false));

        mockMvc.perform(post("/api/actual/import/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileToken\":\"t\",\"currency\":\"EUR\",\"accountMappings\":[],\"categoryMappings\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactionsToDelete").value(1))
            .andExpect(jsonPath("$.warnings[0].reason").value("KEPT_MISSING"))
            .andExpect(jsonPath("$.warnings[0].count").value(3));
    }

    @Test
    void executeRejectsAMalformedCurrencyBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/actual/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileToken\":\"t\",\"currency\":\"euro\",\"accountMappings\":[],\"categoryMappings\":[]}"))
            .andExpect(status().is4xxClientError());

        verifyNoInteractions(service);
    }
}
