package com.dqs.api.service;

import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The EMAIL rows of system_configurations.
 *
 * The values are an administrator's, typed into a table, so every one of them
 * can be blank, missing or nonsense. What matters is that none of those turn
 * into a stack trace at send time, and that the defaults are legacy's own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailSettingsServiceTest {

    @Mock private NativeQueries nativeQueries;

    private EmailSettingsService service() {
        return new EmailSettingsService(nativeQueries);
    }

    private void rows(Map<String, String> config) {
        when(nativeQueries.list(any())).thenReturn(
                config.entrySet().stream()
                        .map(e -> Map.<String, Object>of("config_key", e.getKey(), "config_value", e.getValue()))
                        .toList());
    }

    @Test
    @DisplayName("the configured values are read as they stand in dev today")
    void theConfiguredValuesAreRead() {
        rows(Map.of(
                "EMAIL_ENABLED", "true",
                "SMTP_HOST", "smtp-app.int.pricesmart.com",
                "SMTP_PORT", "25",
                "SMTP_AUTH", "false",
                "EMAIL_SEND_COPY_TO_USER", "false"));

        EmailSettings settings = service().load();

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.host()).isEqualTo("smtp-app.int.pricesmart.com");
        assertThat(settings.port()).isEqualTo(25);
        assertThat(settings.auth()).isFalse();
        assertThat(settings.copyToSender()).isFalse();
    }

    @Test
    @DisplayName("an empty table leaves sending off, not on with legacy's relay")
    void anEmptyTableLeavesSendingOff() {
        when(nativeQueries.list(any())).thenReturn(List.of());

        EmailSettings settings = service().load();

        // The default has to be off. A misconfigured deployment that silently
        // mails customers is worse than one that refuses to.
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.host()).isEqualTo("smtp-app.int.pricesmart.com");
        assertThat(settings.port()).isEqualTo(25);
    }

    @Test
    @DisplayName("a port that is not a number falls back instead of throwing")
    void aNonNumericPortFallsBack() {
        rows(Map.of("EMAIL_ENABLED", "true", "SMTP_PORT", "veinticinco"));

        assertThat(service().load().port()).isEqualTo(25);
    }

    @Test
    @DisplayName("blank values fall back, and 1 counts as true")
    void blanksFallBackAndOneIsTrue() {
        rows(Map.of("EMAIL_ENABLED", "1", "SMTP_HOST", "   ", "SMTP_PORT", ""));

        EmailSettings settings = service().load();

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.host()).isEqualTo("smtp-app.int.pricesmart.com");
        assertThat(settings.port()).isEqualTo(25);
    }
}
