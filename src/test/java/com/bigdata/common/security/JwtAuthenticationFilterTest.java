package com.bigdata.common.security;

import com.bigdata.common.utils.JwtTokenUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 认证过滤器的放行/拒绝分支。
 *
 * <p>覆盖点：无 token、前缀不匹配、有效 token、token 校验返回 false、解析抛异常、
 * AI 请求标记、角色为空时的兜底权限。此前该类的行覆盖只有 4.4%，所有拒绝分支都没被执行过。</p>
 */
@DisplayName("Common模块 - JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    private JwtTokenUtils jwtTokenUtils;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokenUtils = mock(JwtTokenUtils.class);
        filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtTokenUtils", jwtTokenUtils);
        ReflectionTestUtils.setField(filter, "tokenHeader", "Authorization");
        ReflectionTestUtils.setField(filter, "tokenPrefix", "Bearer");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("无 Authorization 头：直接放行，不建立认证")
    void passesThroughWithoutHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "过滤器链应继续执行");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(jwtTokenUtils, never()).validateToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("前缀不是 Bearer：视为未登录请求，直接放行")
    void passesThroughOnUnknownScheme() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("有效 token：写入 userId/username/tenantId/角色权限")
    void authenticatesValidToken() throws Exception {
        stubValidToken("ROLE_SUPER_ADMIN,ROLE_OPS_ENGINEER", 3L, true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
        assertEquals(7L, principal.getUserId());
        assertEquals("alice", principal.getUsername());
        assertEquals(3L, principal.getTenantId());
        assertEquals(2, principal.getRoles().size());
        assertEquals(2, authentication.getAuthorities().size());
        assertFalse(principal.isAiRequest(), "未带 X-AI-Request 头时不应标记为 AI 请求");
    }

    @Test
    @DisplayName("X-AI-Request: true 时主体标记为 AI 请求")
    void marksAiRequestFromHeader() throws Exception {
        stubValidToken("ROLE_USER", null, false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token");
        request.addHeader("X-AI-Request", "true");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        CustomUserDetails principal = (CustomUserDetails)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertTrue(principal.isAiRequest());
    }

    @Test
    @DisplayName("token 中角色为空：兜底为 ROLE_USER")
    void fallsBackToRoleUserWhenRoleClaimMissing() throws Exception {
        stubValidToken(null, null, false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("ROLE_USER", authentication.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    @DisplayName("token 校验返回 false：不建立认证，交给后续过滤器处理")
    void doesNotAuthenticateWhenTokenInvalid() throws Exception {
        when(jwtTokenUtils.validateToken("bad-token")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad-token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest(), "链路应继续，由 Spring Security 决定 401");
    }

    @Test
    @DisplayName("解析 token 抛异常：立刻返回 401 JSON，且不再执行后续链")
    void returnsUnauthorizedWhenParsingThrows() throws Exception {
        when(jwtTokenUtils.validateToken("broken")).thenThrow(new RuntimeException("malformed"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer broken");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), "响应体应包含业务码 401，实际: " + body);
        assertTrue(body.contains("Token无效或已过期"), "响应体应带可读提示，实际: " + body);
        assertNull(chain.getRequest(), "401 后不应继续执行过滤器链");
    }

    private void stubValidToken(String roles, Long tenantId, boolean admin) {
        when(jwtTokenUtils.validateToken("good-token")).thenReturn(true);
        when(jwtTokenUtils.getUserIdFromToken("good-token")).thenReturn(7L);
        when(jwtTokenUtils.getUsernameFromToken("good-token")).thenReturn("alice");
        when(jwtTokenUtils.getRoleFromToken("good-token")).thenReturn(roles);
        when(jwtTokenUtils.getTenantIdFromToken("good-token")).thenReturn(tenantId);
        when(jwtTokenUtils.getIsAdminFromToken("good-token")).thenReturn(admin);
    }
}
