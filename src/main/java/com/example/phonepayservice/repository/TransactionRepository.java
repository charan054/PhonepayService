package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByTransactionId(long transactionId);
    // Everything a person paid, plus completed payments they received - a pending/failed/needs-reconciliation
    // payment is hidden from its receiver, since the money never reliably reached them (see PhonepeService).
    @Query("SELECT t FROM Transaction t WHERE t.phno = :viewer "
            + "OR (t.receiverPhno = :viewer AND (t.status IS NULL OR t.status = com.example.phonepayservice.entity.TransactionStatus.COMPLETED))")
    Page<Transaction> findVisibleTo(@Param("viewer") long viewer, Pageable pageable);
    // null when the table is empty
    @Query("select max(t.transactionId) from Transaction t")
    Long findMaxTransactionId();
}
