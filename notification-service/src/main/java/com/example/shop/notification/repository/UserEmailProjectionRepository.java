package com.example.shop.notification.repository;

import com.example.shop.notification.model.UserEmailProjection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserEmailProjectionRepository extends JpaRepository<UserEmailProjection, Long> {}
