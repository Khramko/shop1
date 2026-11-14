package com.example.shop.notification.service;

import com.example.shop.notification.dto.NotificationResponse;
import com.example.shop.notification.model.Notification;
import com.example.shop.notification.repository.NotificationRepository;
import com.example.shop.notification.repository.UserEmailProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final NotificationRepository repo;
    private final UserEmailProjectionRepository emailRepo;

    public NotificationService(NotificationRepository repo, UserEmailProjectionRepository emailRepo) {
        this.repo = repo;
        this.emailRepo = emailRepo;
    }

    @Transactional
    public void saveHappy(Long userId, Long orderId, java.math.BigDecimal price) {
        String email = resolveEmail(userId);
        Notification n = new Notification(userId, orderId, Notification.Type.HAPPY,
                "Заказ #" + orderId + " оплачен",
                "Здравствуйте! Ваш заказ #" + orderId + " на сумму " + price +
                " успешно оплачен. Письмо счастья отправлено на " + email + ".");
        repo.save(n);
        log.info("HAPPY notification saved userId={} orderId={} email={}", userId, orderId, email);
    }

    @Transactional
    public void saveAngry(Long userId, Long orderId, java.math.BigDecimal price, String reason) {
        String email = resolveEmail(userId);
        Notification n = new Notification(userId, orderId, Notification.Type.ANGRY,
                "Заказ #" + orderId + " отклонён",
                "К сожалению, ваш заказ #" + orderId + " на сумму " + price +
                " не может быть выполнен. Причина: " + reason +
                ". Письмо горя отправлено на " + email + ".");
        repo.save(n);
        log.info("ANGRY notification saved userId={} orderId={} reason={} email={}",
                 userId, orderId, reason, email);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listByUser(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationResponse::from).toList();
    }

    private String resolveEmail(Long userId) {
        return emailRepo.findById(userId).map(e -> e.getEmail()).orElse("unknown@example.com");
    }
}
