package com.DSA.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        
        // Configure recommendations cache (TTL: 3 minutes, Max: 100 entries)
        cacheManager.registerCustomCache("recommendations",
                Caffeine.newBuilder()
                        .expireAfterWrite(3, TimeUnit.MINUTES)
                        .maximumSize(100)
                        .build());
                        
        // Configure user profiles cache (TTL: 5 minutes, Max: 200 entries)
        cacheManager.registerCustomCache("user_profiles",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(200)
                        .build());

        // Configure all-time leaderboard cache (TTL: 10 minutes, Max: 5 entries)
        cacheManager.registerCustomCache("leaderboard_all",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(5)
                        .build());

        // Configure monthly leaderboard cache (TTL: 10 minutes, Max: 5 entries)
        cacheManager.registerCustomCache("leaderboard_month",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(5)
                        .build());

        // Configure weekly leaderboard cache (TTL: 10 minutes, Max: 5 entries)
        cacheManager.registerCustomCache("leaderboard_week",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(5)
                        .build());

        // Configure like counts cache (TTL: 10 minutes, Max: 1000 entries)
        cacheManager.registerCustomCache("like_counts",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(1000)
                        .build());

        // Configure friend counts cache (TTL: 10 minutes, Max: 1000 entries)
        cacheManager.registerCustomCache("friend_counts",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(1000)
                        .build());

        // Configure has liked cache (TTL: 10 minutes, Max: 2000 entries)
        cacheManager.registerCustomCache("has_liked",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(2000)
                        .build());

        // Configure friendship statuses cache (TTL: 10 minutes, Max: 2000 entries)
        cacheManager.registerCustomCache("friendship_statuses",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(2000)
                        .build());

        // Configure all users list cache (TTL: 10 minutes, Max: 1 entry)
        cacheManager.registerCustomCache("all_users_list",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(1)
                        .build());

        // Configure ebook pages cache (TTL: 10 minutes, Max: 20 entries)
        cacheManager.registerCustomCache("ebook_pages",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(20)
                        .build());

        return cacheManager;
    }
}
