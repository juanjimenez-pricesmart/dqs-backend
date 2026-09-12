package com.dqs.api.service;

import com.dqs.api.repository.support.NativeQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the EMAIL rows out of `system_configurations`.
 *
 * That table is shared: our migration declares it, but the rows were written by
 * legacy's administrator and its live shape carries columns ours does not
 * (`code`, `created_date`, `last_modified_by`). So it is read through
 * NativeQueries rather than mapped — an entity would have to match the live
 * table exactly under `ddl-auto=validate`, and we do not own its lifecycle.
 *
 * Read on every send, as legacy does, so switching EMAIL_ENABLED off takes
 * effect without a restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSettingsService {

    private final NativeQueries nativeQueries;

    /** Legacy's own defaults, from EmailService::getEmailConfig. */
    private static final String DEFAULT_HOST = "smtp-app.int.pricesmart.com";
    private static final int DEFAULT_PORT = 25;

    public EmailSettings load() {
        List<Map<String, Object>> rows = nativeQueries.list(
                "SELECT config_key, config_value FROM system_configurations"
                + " WHERE category = 'EMAIL' AND is_active = 1");

        Map<String, String> config = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("config_key");
            if (key != null) {
                Object value = row.get("config_value");
                config.put(key.toString(), value == null ? "" : value.toString().trim());
            }
        }

        EmailSettings settings = EmailSettings.builder()
                .enabled(bool(config.get("EMAIL_ENABLED"), false))
                .host(text(config.get("SMTP_HOST"), DEFAULT_HOST))
                .port(number(config.get("SMTP_PORT"), DEFAULT_PORT))
                .auth(bool(config.get("SMTP_AUTH"), false))
                .user(text(config.get("SMTP_USER"), ""))
                .password(text(config.get("SMTP_PASS"), ""))
                .copyToSender(bool(config.get("EMAIL_SEND_COPY_TO_USER"), false))
                .build();

        log.debug("[EmailSettingsService] enabled={} host={} port={} auth={}",
                settings.enabled(), settings.host(), settings.port(), settings.auth());
        return settings;
    }

    private static boolean bool(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int number(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("[EmailSettingsService] SMTP_PORT is not a number: '{}', using {}", value, fallback);
            return fallback;
        }
    }
}
