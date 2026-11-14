package com.example.shop.common.events;

public record UserCreatedEvent(Long userId, String email, String name) {}