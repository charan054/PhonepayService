package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.PhonepeUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PhonepeUserRepository extends JpaRepository<PhonepeUser, Long> {
    public PhonepeUser findByphno(long phno);
}
