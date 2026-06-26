package com.DSA.common;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OperationTracker {

    private final Firestore firestore;

    // Thread-safe in-memory accumulation counters
    private final AtomicLong pendingReads = new AtomicLong(0);
    private final AtomicLong pendingWrites = new AtomicLong(0);
    private final AtomicLong pendingDeletes = new AtomicLong(0);
    private final AtomicLong pendingR2ClassA = new AtomicLong(0);
    private final AtomicLong pendingR2ClassB = new AtomicLong(0);

    public OperationTracker(@Lazy Firestore firestore) {
        this.firestore = firestore;
    }

    public void trackRead() {
        pendingReads.incrementAndGet();
    }

    public void trackReads(long count) {
        if (count > 0) {
            pendingReads.addAndGet(count);
        }
    }

    public void trackWrite() {
        pendingWrites.incrementAndGet();
    }

    public void trackWrites(long count) {
        if (count > 0) {
            pendingWrites.addAndGet(count);
        }
    }

    public void trackDelete() {
        pendingDeletes.incrementAndGet();
    }

    public void trackDeletes(long count) {
        if (count > 0) {
            pendingDeletes.addAndGet(count);
        }
    }

    public void trackR2ClassA() {
        pendingR2ClassA.incrementAndGet();
    }

    public void trackR2ClassB() {
        pendingR2ClassB.incrementAndGet();
    }

    // Flush to Firestore every 2 minutes (120000 milliseconds)
    @Scheduled(fixedRate = 120000)
    public void flush() {
        long reads = pendingReads.getAndSet(0);
        long writes = pendingWrites.getAndSet(0);
        long deletes = pendingDeletes.getAndSet(0);
        long r2A = pendingR2ClassA.getAndSet(0);
        long r2B = pendingR2ClassB.getAndSet(0);

        if (reads == 0 && writes == 0 && deletes == 0 && r2A == 0 && r2B == 0) {
            return;
        }

        try {
            String today = LocalDate.now(ZoneId.of("UTC")).toString(); // "YYYY-MM-DD"
            DocumentReference docRef = firestore.collection("operation_stats").document(today);

            Map<String, Object> updates = new HashMap<>();
            if (reads > 0) updates.put("firestoreReads", FieldValue.increment(reads));
            if (writes > 0) updates.put("firestoreWrites", FieldValue.increment(writes));
            if (deletes > 0) updates.put("firestoreDeletes", FieldValue.increment(deletes));
            if (r2A > 0) updates.put("r2ClassA", FieldValue.increment(r2A));
            if (r2B > 0) updates.put("r2ClassB", FieldValue.increment(r2B));

            docRef.set(updates, SetOptions.merge()).get();
        } catch (Exception e) {
            System.err.println("⚠️ Failed to flush operation stats to Firestore: " + e.getMessage());
            // Restore values if flush failed
            pendingReads.addAndGet(reads);
            pendingWrites.addAndGet(writes);
            pendingDeletes.addAndGet(deletes);
            pendingR2ClassA.addAndGet(r2A);
            pendingR2ClassB.addAndGet(r2B);
        }
    }
}
