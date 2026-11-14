package com.example.shop.user.controller;

import com.example.shop.common.security.AuthenticatedUser;
import com.example.shop.user.dto.ProfileUpdateRequest;
import com.example.shop.user.dto.UserResponse;
import com.example.shop.user.model.User;
import com.example.shop.user.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserRepository userRepository;

    public ProfileController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        User u = userRepository.findById(principal.id()).orElseThrow();
        return ResponseEntity.ok(new UserResponse(u.getId(), u.getName(), u.getEmail()));
    }

    @PutMapping
    public ResponseEntity<UserResponse> update(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @Valid @RequestBody ProfileUpdateRequest req) {
        User u = userRepository.findById(principal.id()).orElseThrow();
        u.setName(req.getName());
        userRepository.save(u);
        return ResponseEntity.ok(new UserResponse(u.getId(), u.getName(), u.getEmail()));
    }
}
