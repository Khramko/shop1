package com.example.shop.delivery.service;

import com.example.shop.delivery.model.CourierSlot;
import com.example.shop.delivery.model.Reservation;
import com.example.shop.delivery.repository.CourierSlotRepository;
import com.example.shop.delivery.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private final CourierSlotRepository slotRepo;
    private final ReservationRepository reservationRepo;

    public DeliveryService(CourierSlotRepository slotRepo, ReservationRepository reservationRepo) {
        this.slotRepo = slotRepo; this.reservationRepo = reservationRepo;
    }

    /** Возвращает courierId при успехе, либо null + reason через исключение. */
    @Transactional
    public Long reserve(Long orderId, String slot) {
        if (reservationRepo.existsById(orderId)) {
            Reservation r = reservationRepo.findById(orderId).get();
            log.info("Reservation for order {} already exists — idempotent skip", orderId);
            return r.getCourierId();
        }
        CourierSlot cs = slotRepo.findFirstBySlotAndStatus(slot, CourierSlot.Status.FREE).orElse(null);
        if (cs == null) throw new IllegalStateException("NO_COURIER_AVAILABLE");
        cs.setStatus(CourierSlot.Status.RESERVED);
        slotRepo.save(cs);
        reservationRepo.save(new Reservation(orderId, cs.getCourierId(), slot));
        log.info("Reserved courier={} slot={} order={}", cs.getCourierId(), slot, orderId);
        return cs.getCourierId();
    }

    @Transactional
    public void release(Long orderId) {
        Reservation r = reservationRepo.findById(orderId).orElse(null);
        if (r == null || r.getStatus() == Reservation.Status.RELEASED) {
            log.info("Release order={} — nothing to release (idempotent)", orderId);
            return;
        }
        slotRepo.findAll().stream()
            .filter(cs -> cs.getCourierId().equals(r.getCourierId())
                      && cs.getSlot().equals(r.getSlot()))
            .forEach(cs -> { cs.setStatus(CourierSlot.Status.FREE); slotRepo.save(cs); });
        r.setStatus(Reservation.Status.RELEASED);
        reservationRepo.save(r);
        log.info("Released order={} courier={} slot={}", orderId, r.getCourierId(), r.getSlot());
    }
}
