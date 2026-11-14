package com.example.shop.warehouse.service;

import com.example.shop.warehouse.model.Reservation;
import com.example.shop.warehouse.model.Stock;
import com.example.shop.warehouse.repository.ReservationRepository;
import com.example.shop.warehouse.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseService.class);
    private final StockRepository stockRepo;
    private final ReservationRepository reservationRepo;

    public WarehouseService(StockRepository stockRepo, ReservationRepository reservationRepo) {
        this.stockRepo = stockRepo; this.reservationRepo = reservationRepo;
    }

    /** Возвращает null при успехе, либо причину отказа. */
    @Transactional
    public String reserve(Long orderId, String sku, int qty) {
        if (reservationRepo.existsById(orderId)) {
            log.info("Reservation for order {} already exists — idempotent skip", orderId);
            return null;
        }
        Stock s = stockRepo.findById(sku).orElse(null);
        if (s == null) return "SKU_NOT_FOUND";
        if (s.getQtyAvailable() < qty) return "OUT_OF_STOCK";
        s.setQtyAvailable(s.getQtyAvailable() - qty);
        s.setQtyReserved(s.getQtyReserved() + qty);
        stockRepo.save(s);
        reservationRepo.save(new Reservation(orderId, sku, qty));
        log.info("Reserved order={} sku={} qty={} left={}", orderId, sku, qty, s.getQtyAvailable());
        return null;
    }

    @Transactional
    public void release(Long orderId) {
        Reservation r = reservationRepo.findById(orderId).orElse(null);
        if (r == null || r.getStatus() == Reservation.Status.RELEASED) {
            log.info("Release order={} — nothing to release (idempotent)", orderId);
            return;
        }
        Stock s = stockRepo.findById(r.getSku()).orElseThrow();
        s.setQtyAvailable(s.getQtyAvailable() + r.getQty());
        s.setQtyReserved(s.getQtyReserved() - r.getQty());
        stockRepo.save(s);
        r.setStatus(Reservation.Status.RELEASED);
        reservationRepo.save(r);
        log.info("Released order={} sku={} qty={}", orderId, r.getSku(), r.getQty());
    }
}
