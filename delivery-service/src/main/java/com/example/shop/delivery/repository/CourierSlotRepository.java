package com.example.shop.delivery.repository;

import com.example.shop.delivery.model.CourierSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CourierSlotRepository extends JpaRepository<CourierSlot, Long> {
    Optional<CourierSlot> findFirstBySlotAndStatus(String slot, CourierSlot.Status status);
}
