package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.PaymentDispute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentDisputeRepository extends JpaRepository<PaymentDispute, Long> {
    Optional<PaymentDispute> findByTransactionId(long transactionId);
    // newest first
    List<PaymentDispute> findByPhnoOrderByIdDesc(long phno);
    // oldest first: the staff queue works from the longest-waiting complaint
    List<PaymentDispute> findByStatusOrderByIdAsc(String status);
}
