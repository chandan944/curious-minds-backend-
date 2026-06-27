package com.DSA.user;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;
import com.DSA.common.IdGenerator;

import java.util.*;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
@SuppressWarnings("null")
public class UserRepository {

    private final Firestore firestore;
    private final com.DSA.common.OperationTracker operationTracker;

    public Optional<User> findById(Long id) {
        if (id == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            DocumentSnapshot doc = firestore.collection("users")
                    .document(String.valueOf(id))
                    .get().get();
            if (doc.exists()) {
                return Optional.of(doc.toObject(User.class));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in UserRepository.findById: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<User> findByEmail(String email) {
        if (email == null) return Optional.empty();
        operationTracker.trackRead(); // Query counts as 1 read
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("users")
                    .whereEqualTo("email", email.trim().toLowerCase())
                    .limit(1)
                    .get().get().getDocuments();
            if (!docs.isEmpty()) {
                operationTracker.trackRead(); // Document read counts as 1 read
                return Optional.of(docs.get(0).toObject(User.class));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in UserRepository.findByEmail: " + e.getMessage());
        }
        return Optional.empty();
    }

    @org.springframework.cache.annotation.Caching(evict = {
        @org.springframework.cache.annotation.CacheEvict(value = "all_users_list", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "user_profiles", allEntries = true)
    })
    public User save(User user) {
        if (user == null) return null;
        if (user.getId() == null) {
            user.setId(IdGenerator.generateId());
        }
        if (user.getEmail() != null) {
            user.setEmail(user.getEmail().trim().toLowerCase());
        }
        operationTracker.trackWrite();
        try {
            firestore.collection("users")
                    .document(String.valueOf(user.getId()))
                    .set(user).get();
        } catch (Exception e) {
            System.err.println("❌ Error in UserRepository.save: " + e.getMessage());
        }
        return user;
    }

    @org.springframework.cache.annotation.Caching(evict = {
        @org.springframework.cache.annotation.CacheEvict(value = "all_users_list", allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = "user_profiles", allEntries = true)
    })
    public void delete(User user) {
        if (user != null && user.getId() != null) {
            operationTracker.trackDelete();
            try {
                firestore.collection("users")
                        .document(String.valueOf(user.getId()))
                        .delete().get();
            } catch (Exception e) {
                System.err.println("❌ Error in UserRepository.delete: " + e.getMessage());
            }
        }
    }

    public List<User> findAll() {
        List<User> list = new ArrayList<>();
        operationTracker.trackRead(); // Collection query count
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("users")
                    .get().get().getDocuments();
            operationTracker.trackReads(docs.size());
            for (QueryDocumentSnapshot doc : docs) {
                list.add(doc.toObject(User.class));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in UserRepository.findAll: " + e.getMessage());
        }
        return list;
    }

    @org.springframework.cache.annotation.Cacheable(value = "all_users_list")
    public List<User> findAllCached() {
        return findAll();
    }

    public Page<User> findAllByOrderByPointsDesc(Pageable pageable) {
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();
        operationTracker.trackRead(); // Aggregation count query
        try {
            long total = firestore.collection("users").count().get().get().getCount();
            operationTracker.trackRead(); // Query call
            List<QueryDocumentSnapshot> docs = firestore.collection("users")
                    .orderBy("points", Query.Direction.DESCENDING)
                    .limit(limit)
                    .offset(offset)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            List<User> users = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                users.add(doc.toObject(User.class));
            }
            return new PageImpl<>(users, pageable, total);
        } catch (Exception e) {
            System.err.println("❌ Error in UserRepository.findAllByOrderByPointsDesc: " + e.getMessage());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    public Page<User> fuzzySearchUsers(String exactQuery, String likeQuery, String fuzzyPattern, Long currentUserId, Pageable pageable) {
        operationTracker.trackRead(); // Query call
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection("users")
                    .get().get().getDocuments();
            
            operationTracker.trackReads(docs.size());
            List<User> allUsers = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                User u = doc.toObject(User.class);
                if (u.getId() != null && !u.getId().equals(currentUserId)) {
                    allUsers.add(u);
                }
            }

            String term = exactQuery.toLowerCase().trim();
            String cleanTerm = term.replace(" ", "");

            List<User> matched = allUsers.stream()
                    .filter(u -> {
                        String name = u.getName() != null ? u.getName().toLowerCase() : "";
                        String email = u.getEmail() != null ? u.getEmail().toLowerCase() : "";
                        String cleanName = name.replace(" ", "");
                        return name.contains(term) || email.contains(term) || cleanName.contains(cleanTerm);
                    })
                    .sorted((u1, u2) -> {
                        String name1 = u1.getName() != null ? u1.getName().toLowerCase() : "";
                        String name2 = u2.getName() != null ? u2.getName().toLowerCase() : "";

                        int score1 = getNameMatchScore(name1, term);
                        int score2 = getNameMatchScore(name2, term);

                        if (score1 != score2) {
                            return Integer.compare(score1, score2);
                        }
                        return name1.compareTo(name2);
                    })
                    .collect(Collectors.toList());

            int total = matched.size();
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), total);

            List<User> pageContent = (start < total) ? matched.subList(start, end) : Collections.emptyList();
            return new PageImpl<>(pageContent, pageable, total);
        } catch (Exception e) {
            System.err.println("❌ Error in UserRepository.fuzzySearchUsers: " + e.getMessage());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    public List<User> findAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();

        List<String> docPaths = ids.stream()
                .distinct()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toList());

        if (docPaths.isEmpty()) return Collections.emptyList();

        operationTracker.trackReads(docPaths.size());
        try {
            com.google.cloud.firestore.DocumentReference[] refs = docPaths.stream()
                    .map(path -> firestore.collection("users").document(path))
                    .toArray(com.google.cloud.firestore.DocumentReference[]::new);

            return firestore.getAll(refs).get().stream()
                    .filter(doc -> doc.exists())
                    .map(doc -> doc.toObject(User.class))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            System.err.println("❌ Error in UserRepository.findAllByIds: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private int getNameMatchScore(String name, String term) {
        if (name.equals(term)) return 1;
        if (name.startsWith(term)) return 2;
        if (name.contains(term)) return 3;
        return 4;
    }
}
