package com.picsou.controller;

import com.picsou.dto.HomeBankImportDtos.*;
import com.picsou.service.HomeBankImportService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.util.HashMap;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HomeBankImportControllerTest {
    @Mock HomeBankImportService service;
    @Mock UserContext userContext;

    @Test
    void previewPassesOptionalPasswordAndAuthenticatedMemberToService() {
        var controller=new HomeBankImportController(service,userContext,new HashMap<>());
        var file=new MockMultipartFile("file","a.hbk","application/octet-stream",new byte[]{1});
        var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");
        when(userContext.currentMemberId()).thenReturn(42L);
        when(service.preview(file,"secret",42L)).thenReturn(new Preview("token",List.of(),List.of(),List.of(),List.of(),List.of(),0,0));
        var response=controller.preview(file,"secret",request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).preview(file,"secret",42L);
    }

    @Test
    void executeReturnsCreatedAndMemberScopedResult() {
        var controller=new HomeBankImportController(service,userContext,new HashMap<>());
        var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");
        var body=new Request("token",List.of(),List.of());var expected=new Result(1,0,0,0,0,0);
        when(userContext.currentMemberId()).thenReturn(42L);
        when(service.executeImport(body,42L)).thenReturn(expected);
        var response=controller.execute(body,request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(service).executeImport(body,42L);
    }

    @Test
    void executeRejectsNullMappingElementsOverHttpBeforeCallingService() throws Exception {
        var controller = new HomeBankImportController(service, userContext, new HashMap<>());
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();

        mvc.perform(post("/api/homebank/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileToken\":\"token\",\"accountMappings\":[null],\"categoryMappings\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
