package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.LoginData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoginDataRepository extends JpaRepository<LoginData,Long> {
}
