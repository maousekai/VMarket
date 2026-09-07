package com.vmarket.auth.email;

/**
 * Một email giao dịch cần gửi. Tách khỏi provider để {@link EmailSender} đổi nhà
 * cung cấp không ảnh hưởng chỗ gọi.
 */
public record EmailMessage(String to, String subject, String htmlBody, String textBody) {
}
