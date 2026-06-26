package com.DSA.ebook;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class EbookRepository {

    private final Firestore firestore;
    private final com.DSA.common.OperationTracker operationTracker;

    public Optional<Ebook> findById(Long id) {
        if (id == null) return Optional.empty();
        operationTracker.trackRead();
        try {
            DocumentSnapshot doc = firestore.collection("ebooks")
                    .document(String.valueOf(id))
                    .get().get();
            if (doc.exists()) {
                return Optional.of(doc.toObject(Ebook.class));
            }
        } catch (Exception e) {
            System.err.println("❌ Error in EbookRepository.findById: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Ebook save(Ebook ebook) {
        if (ebook == null) return null;
        if (ebook.getId() == null) {
            ebook.setId(IdGenerator.generateId());
        }
        operationTracker.trackWrite();
        try {
            firestore.collection("ebooks")
                    .document(String.valueOf(ebook.getId()))
                    .set(ebook).get();
        } catch (Exception e) {
            System.err.println("❌ Error in EbookRepository.save: " + e.getMessage());
        }
        return ebook;
    }

    public void delete(Ebook ebook) {
        if (ebook != null && ebook.getId() != null) {
            operationTracker.trackDelete();
            try {
                firestore.collection("ebooks")
                        .document(String.valueOf(ebook.getId()))
                        .delete().get();
            } catch (Exception e) {
                System.err.println("❌ Error in EbookRepository.delete: " + e.getMessage());
            }
        }
    }

    public Page<Ebook> findAllByOrderByUploadedAtDesc(Pageable pageable) {
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();
        operationTracker.trackRead(); // Aggregation count query
        try {
            long total = firestore.collection("ebooks").count().get().get().getCount();
            operationTracker.trackRead(); // Query call
            List<QueryDocumentSnapshot> docs = firestore.collection("ebooks")
                    .orderBy("uploadedAt", Query.Direction.DESCENDING)
                    .limit(limit)
                    .offset(offset)
                    .get().get().getDocuments();

            operationTracker.trackReads(docs.size());
            List<Ebook> ebooks = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                ebooks.add(doc.toObject(Ebook.class));
            }
            return new PageImpl<>(ebooks, pageable, total);
        } catch (Exception e) {
            System.err.println("❌ Error in EbookRepository.findAllByOrderByUploadedAtDesc: " + e.getMessage());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }
}
