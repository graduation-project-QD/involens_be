package org.involens_be.controller;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.dto.response.VendorListItemResponse;
import org.involens_be.service.VendorService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Verifies supplier dropdown serialization and token-based ownership for both roles. */
@Slf4j
class VendorControllerTest {
    /** Uses the token uploader for employees and managers even when a different userId is supplied. */
    @Test
    void returnsSupplierOptionsForAuthenticatedUploader() throws Exception {
        log.info("Testing supplier HTTP contract and role-independent uploader scope");
        var service = mock(VendorService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new VendorController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        when(service.listVendors(12L, "ABC"))
                .thenReturn(List.of(new VendorListItemResponse(7L, "ABC")));
        when(service.listVendors(12L, null)).thenReturn(List.of());
        try {
            for (String role : List.of("EMPLOYEE", "MANAGER")) {
                var jwt = Jwt.withTokenValue("test").header("alg", "HS256")
                        .claim("userId", 12L).claim("role", role).build();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
                mvc.perform(get("/api/v1/vendors").param("q", "ABC").param("userId", "99"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0))
                        .andExpect(jsonPath("$.data[0].id").value(7))
                        .andExpect(jsonPath("$.data[0].name").value("ABC"))
                        .andExpect(jsonPath("$.data.length()").value(1));
                mvc.perform(get("/api/v1/vendors"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
            }
            verify(service, times(2)).listVendors(12L, "ABC");
            verify(service, times(2)).listVendors(12L, null);
            verifyNoMoreInteractions(service);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
