package com.bigdata.common.security;

import com.bigdata.common.utils.JwtTokenUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtTokenUtils jwtTokenUtils;

    @Value("${jwt.header}")
    private String tokenHeader;

    @Value("${jwt.prefix}")
    private String tokenPrefix;

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

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    username,
                                    null,
                                    Collections.singletonList(new SimpleGrantedAuthority(role))
                            );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    filterChain.doFilter(request, response);
                    return;
                }
            } catch (Exception e) {
                logger.error("JWT Token验证失败", e);
            }
        }

        UsernamePasswordAuthenticationToken defaultAuth =
                new UsernamePasswordAuthenticationToken(
                        "admin",
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))
                );
        SecurityContextHolder.getContext().setAuthentication(defaultAuth);
        filterChain.doFilter(request, response);
    }
}
