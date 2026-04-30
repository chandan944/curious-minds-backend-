package com.DSA.config;

import com.DSA.auth.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = header.substring(7);
            Claims claims = jwtService.extractClaims(token);

            String email = claims.get("email", String.class);
            String role = claims.get("role", String.class);
            Long userId = claims.get("userId", Long.class);

            if (email == null || userId == null) {
                System.out.println("❌ Email or userId null in JWT claims!");
                filterChain.doFilter(request, response);
                return;
            }

            System.out.println("✅ Authenticated user: " + email + " (ID: " + userId + ")");

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    email,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );

            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);

        } catch (Exception e) {
            System.out.println("⚠ JWT Error: " + e.getMessage());
            // Don't block the request — let Spring Security authorization decide
            // This allows permitAll() endpoints to work even with bad tokens
            filterChain.doFilter(request, response);
        }
    }
}
