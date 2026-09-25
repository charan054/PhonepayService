package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.MoneyRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MoneyRequestRepository extends JpaRepository<MoneyRequest, Long> {
    // Everything this person is involved in, either side, newest first.
    List<MoneyRequest> findByRequesterPhnoOrPayerPhnoOrderByIdDesc(long requesterPhno, long payerPhno);
}
