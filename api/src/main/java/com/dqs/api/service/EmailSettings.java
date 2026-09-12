package com.dqs.api.service;

import lombok.Builder;

/**
 * The SMTP settings, as they are configured today: an internal relay on port 25
 * with no authentication.
 *
 * They live in `system_configurations`, category EMAIL, which legacy's
 * EmailModel reads through the same keys. Not in application.properties: an
 * administrator changes them there without a deployment, and legacy re-reads
 * them on every send.
 */
@Builder
public record EmailSettings(
        boolean enabled,
        String host,
        int port,
        boolean auth,
        String user,
        String password,
        boolean copyToSender) {
}
