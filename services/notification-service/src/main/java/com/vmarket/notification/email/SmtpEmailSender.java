package com.vmarket.notification.email;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.vmarket.notification.config.NotificationProperties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Gửi email qua SMTP (SRS §6 — vd Gmail SMTP cho demo). Kích hoạt khi
 * {@code app.notification.email.provider=smtp}; cấu hình máy chủ ở {@code spring.mail.*}.
 */
@Component
@ConditionalOnProperty(name = "app.notification.email.provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

	private final JavaMailSender mailSender;
	private final String from;
	private final String fromName;

	public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSender, NotificationProperties properties) {
		this.mailSender = mailSender.getIfAvailable();
		if (this.mailSender == null) {
			throw new IllegalStateException("app.notification.email.provider=smtp nhưng thiếu SMTP_HOST (spring.mail.host)");
		}
		this.from = properties.getEmail().getFrom();
		this.fromName = properties.getEmail().getFromName();
	}

	@Override
	public String provider() {
		return "smtp";
	}

	@Override
	public void send(EmailMessage message) {
		try {
			MimeMessage mime = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
			helper.setFrom(from, fromName);
			helper.setTo(message.to());
			helper.setSubject(message.subject());
			helper.setText(message.textBody(), message.htmlBody());
			mailSender.send(mime);
		} catch (MailException | MessagingException | UnsupportedEncodingException ex) {
			throw new EmailDeliveryException("SMTP từ chối hoặc không kết nối được: " + ex.getClass().getSimpleName(), ex);
		}
	}
}
