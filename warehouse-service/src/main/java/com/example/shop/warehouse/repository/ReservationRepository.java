package com.example.shop.warehouse.repository;

import com.example.shop.warehouse.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {}
