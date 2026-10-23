package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.PaymentMandate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentMandateRepository extends JpaRepository<PaymentMandate, Long> {
    List<PaymentMandate> findByPayerPhnoOrderByIdDesc(long payerPhno);
    long countByPayerPhnoAndStatus(long payerPhno, String status);
}
