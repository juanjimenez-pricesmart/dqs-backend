package com.dqs.api.service;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Builds a sender from settings read at send time.
 *
 * Not a `spring.mail.*` bean: the settings live in the database and an
 * administrator can change them without a deployment, so a bean fixed at
 * startup would hold a stale host. Its own type so a test can hand
 * QuoteEmailService a sender that never opens a socket — the real relay is
 * internal to PriceSmart and unreachable from a developer machine.
 */
@Component
public class MailSenderFactory {

    public JavaMailSender create(EmailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setDefaultEncoding("UTF-8");

        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", String.valueOf(settings.auth()));

        if (settings.auth()) {
            sender.setUsername(settings.user());
            sender.setPassword(settings.password());
        }
        return sender;
    }
}
