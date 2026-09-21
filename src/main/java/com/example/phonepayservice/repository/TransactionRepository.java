package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByTransactionId(long transactionId);
    // everything a person paid or received, newest first
    List<Transaction> findByPhnoOrReceiverPhnoOrderByIdDesc(long phno, Long receiverPhno);
    // null when the table is empty
    @Query("select max(t.transactionId) from Transaction t")
    Long findMaxTransactionId();
}
