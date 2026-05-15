package com.bigdata.common.security;

import com.bigdata.common.utils.JwtTokenUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Value("${jwt.header}")
    private String tokenHeader;

    @Value("${jwt.prefix}")
    private String tokenPrefix;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(tokenHeader);
        if (StringUtils.hasText(header) && header.startsWith(tokenPrefix)) {
            String authToken = header.substring(tokenPrefix.length() + 1);
            try {
                if (jwtTokenUtils.validateToken(authToken)) {
                    Long userId = jwtTokenUtils.getUserIdFromToken(authToken);
                    String username = jwtTokenUtils.getUsernameFromToken(authToken);
                    String role = jwtTokenUtils.getRoleFromToken(authToken);

                    List<SimpleGrantedAuthority> authorities = parseAuthorities(role);

                    CustomUserDetails userDetails = new CustomUserDetails(
                            userId, username, null, parseRoleNames(role),
                            authorities, true
                    );

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    authorities
                            );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    filterChain.doFilter(request, response);
                    return;
                }
            } catch (Exception e) {
                logger.error("JWT Token验证失败", e);
                sendUnauthorizedResponse(response, "Token无效或已过期");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> parseAuthorities(String role) {
        if (role == null || role.isEmpty()) {
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }
        return Arrays.stream(role.split(","))
                .map(r -> new SimpleGrantedAuthority(r.trim()))
                .collect(Collectors.toList());
    }

    private List<String> parseRoleNames(String role) {
        if (role == null || role.isEmpty()) {
            return Collections.singletonList("ROLE_USER");
        }
        return Arrays.stream(role.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("code", 401);
        result.put("message", message);
        result.put("data", null);

        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
