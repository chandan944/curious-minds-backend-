package com.DSA.controller;

import com.DSA.user.PointTransactionRepository;
import com.DSA.user.User;
import com.DSA.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.Cacheable;

@RestController
@RequestMapping("/leaderboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LeaderboardController {

    private final UserRepository userRepository;
    private final PointTransactionRepository pointTransactionRepository;

    // ── All-Time Leaderboard ───────────────────────────────────────────────────
    @GetMapping("/all")
    @Cacheable(value = "leaderboard_all", key = "#page + '_' + #size")
    public ResponseEntity<?> getAllTimeLeaderboard(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<User> result = userRepository.findAllByOrderByPointsDesc(PageRequest.of(page, size));
        AtomicInteger rankOffset = new AtomicInteger(page * size + 1);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(u -> Map.<String, Object>of(
                        "rank", rankOffset.getAndIncrement(),
                        "id", u.getId(),
                        "idString", u.getIdString(),
                        "name", u.getName(),
                        "imageUrl", u.getImageUrl() != null ? u.getImageUrl() : "",
                        "level", u.getLevel(),
                        "title", u.getTitle(),
                        "points", u.getPoints()
                )).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "content", content,
                "totalPages", result.getTotalPages(),
                "totalElements", result.getTotalElements(),
                "currentPage", page
        ));
    }

    // ── Time-windowed Leaderboards ─────────────────────────────────────────────
    @GetMapping("/month")
    @Cacheable(value = "leaderboard_month", key = "#page + '_' + #size")
    public ResponseEntity<?> getMonthLeaderboard(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return getWindowedLeaderboard(Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS), page, size);
    }

    @GetMapping("/week")
    @Cacheable(value = "leaderboard_week", key = "#page + '_' + #size")
    public ResponseEntity<?> getWeekLeaderboard(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return getWindowedLeaderboard(Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS), page, size);
    }

    // ── Internal helper ────────────────────────────────────────────────────────
    private ResponseEntity<?> getWindowedLeaderboard(Instant from, int page, int size) {
        Page<Object[]> result = pointTransactionRepository.findTopUsersByPointsSince(
                from, PageRequest.of(page, size));

        AtomicInteger rankOffset = new AtomicInteger(page * size + 1);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(row -> Map.<String, Object>of(
                        "rank", rankOffset.getAndIncrement(),
                        "id", row[0],
                        "idString", row[0] != null ? String.valueOf(row[0]) : "",
                        "name", row[1] != null ? row[1] : "",
                        "imageUrl", row[2] != null ? row[2] : "",
                        "level", row[3] != null ? row[3] : 1,
                        "title", row[4] != null ? row[4] : "Curious Kid",
                        "points", row[5] != null ? ((Number) row[5]).intValue() : 0
                )).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "content", content,
                "totalPages", result.getTotalPages(),
                "totalElements", result.getTotalElements(),
                "currentPage", page
        ));
    }
}
