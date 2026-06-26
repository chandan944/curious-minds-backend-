package com.DSA.common;

import java.security.SecureRandom;

public class IdGenerator {
    private static final SecureRandom random = new SecureRandom();

    public static Long generateId() {
        long id;
        do {
            id = random.nextLong() & Long.MAX_VALUE; // Ensure positive
        } while (id == 0);
        return id;
    }
}
