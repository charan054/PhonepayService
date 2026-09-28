package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.UpiCollectRequest;
import com.example.phonepayservice.entity.UpiCollectRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UpiCollectRequestRepository extends JpaRepository<UpiCollectRequest, Long> {
    Optional<UpiCollectRequest> findByMerchantReference(String merchantReference);
    List<UpiCollectRequest> findByPayerPhnoAndStatusOrderByIdDesc(long payerPhno, UpiCollectRequestStatus status);
}
