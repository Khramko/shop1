package com.example.shop.delivery.repository;

import com.example.shop.delivery.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {}
