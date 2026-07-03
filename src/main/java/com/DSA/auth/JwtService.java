package com.DSA.auth;

import com.DSA.common.JwtConstants;
import com.DSA.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    @Value("${jwt.secret.key}")
    private String secret;

    public SecretKey getKey(){
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if(keyBytes.length <32){
            throw new IllegalArgumentException("Secret key must be at least 256 bits (32 characters) long");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
    // Generate JWT token
    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("email", user.getEmail())
                .claim("userId", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + JwtConstants.EXPIRATION_TIME))
                .signWith(getKey())
                .compact();
    }




    // Extract claims from JWT
    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(Claims claims) {
        Object userIdObj = claims.get("userId");
        if (userIdObj instanceof Number) {
            return ((Number) userIdObj).longValue();
        }
        return null;
    }



}
