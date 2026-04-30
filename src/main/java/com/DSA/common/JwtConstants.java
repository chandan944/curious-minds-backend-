package com.DSA.common;

public class JwtConstants {

    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String HEADER_STRING = "Authorization";
    public static final String AUTHORITIES_KEY = "roles";

    public static final String SECRET_KEY =
            "super-secret-key-change-this-in-production-please";

    public static final long EXPIRATION_TIME = 1000L * 60 * 60 * 24 * 30 * 60; // 24 hours
}
