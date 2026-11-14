package com.example.shop.order.repository;

import com.example.shop.order.model.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStateRepository extends JpaRepository<SagaState, Long> {}
