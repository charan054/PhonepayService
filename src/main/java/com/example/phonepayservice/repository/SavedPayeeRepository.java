package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.SavedPayee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedPayeeRepository extends JpaRepository<SavedPayee, Long> {
    // most recently saved/updated first
    List<SavedPayee> findByOwnerPhnoOrderByIdDesc(long ownerPhno);
    Optional<SavedPayee> findByOwnerPhnoAndPayeePhno(long ownerPhno, long payeePhno);
    // 0 when there was nothing to delete
    long deleteByOwnerPhnoAndPayeePhno(long ownerPhno, long payeePhno);
}
