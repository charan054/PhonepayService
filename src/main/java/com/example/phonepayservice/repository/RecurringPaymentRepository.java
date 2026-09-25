package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.RecurringPayment;
import com.example.phonepayservice.entity.RecurringPaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RecurringPaymentRepository extends JpaRepository<RecurringPayment, Long> {
    // most recently created first
    List<RecurringPayment> findByOwnerPhnoOrderByIdDesc(long ownerPhno);

    // what the scheduler runs: every ACTIVE row whose scheduled time has arrived
    List<RecurringPayment> findByStatusAndNextRunAtLessThanEqual(RecurringPaymentStatus status, Instant now);
}
