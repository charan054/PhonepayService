package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByTransactionId(long transactionId);
    // Everything a person paid, plus completed payments they received - a pending/failed/needs-reconciliation
    // payment is hidden from its receiver, since the money never reliably reached them (see PhonepeService).
    // from/to are optional: the (:from IS NULL OR ...) form lets one query serve both the filtered and
    // unfiltered case, rather than needing a second query just for when no date range is given.
    @Query("SELECT t FROM Transaction t WHERE (t.phno = :viewer "
            + "OR (t.receiverPhno = :viewer AND (t.status IS NULL OR t.status = com.example.phonepayservice.entity.TransactionStatus.COMPLETED))) "
            + "AND (:from IS NULL OR t.createdAt >= :from) "
            + "AND (:to IS NULL OR t.createdAt <= :to)")
    Page<Transaction> findVisibleTo(@Param("viewer") long viewer, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
    // null when the table is empty
    @Query("select max(t.transactionId) from Transaction t")
    Long findMaxTransactionId();
}
