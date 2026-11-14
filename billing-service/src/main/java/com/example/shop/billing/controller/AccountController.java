package com.example.shop.billing.controller;

import com.example.shop.billing.dto.AccountResponse;
import com.example.shop.billing.dto.DepositRequest;
import com.example.shop.billing.service.AccountService;
import com.example.shop.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/billing/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) { this.accountService = accountService; }

    @GetMapping("/me")
    public ResponseEntity<AccountResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(accountService.getByUserId(principal.id()));
    }

    @PostMapping("/me/deposit")
    public ResponseEntity<AccountResponse> deposit(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @Valid @RequestBody DepositRequest req) {
        return ResponseEntity.ok(accountService.deposit(principal.id(), req.getAmount()));
    }
}
