package com.example.shop.notification.model;

import jakarta.persistence.*;

@Entity
@Table(name = "user_email_projection")
public class UserEmailProjection {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String email;

    public UserEmailProjection() {}
    public UserEmailProjection(Long userId, String email) { this.userId = userId; this.email = email; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
