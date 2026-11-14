package com.example.shop.notification.controller;

import com.example.shop.common.security.AuthenticatedUser;
import com.example.shop.notification.dto.NotificationResponse;
import com.example.shop.notification.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(service.listByUser(principal.id()));
    }
}
