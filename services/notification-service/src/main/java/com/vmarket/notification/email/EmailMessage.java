package com.vmarket.notification.email;

/** Một email giao dịch cần gửi — tách khỏi provider. */
public record EmailMessage(String to, String subject, String htmlBody, String textBody) {
}
