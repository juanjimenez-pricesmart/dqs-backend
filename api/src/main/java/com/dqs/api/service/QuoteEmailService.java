package com.dqs.api.service;

import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.exception.EmailNotSentException;
import com.dqs.api.repository.MemberContactOverrideRepository;
import com.dqs.api.repository.support.NativeQueries;
import com.dqs.api.util.ClubMarketingFooter;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Emails the quotation to the member.
 *
 * Legacy does this through the PDF endpoint: enviarcorreo() opens
 * orders/printDiv/{id}/2/{orden} in a popup, target 2 meaning "email", and
 * closes the popup after three seconds unless its HTML happens to contain the
 * word "error". The send is a side effect of rendering a page. Here it is a
 * request that answers whether it worked.
 *
 * Two other differences.
 *
 * The quotation travels as a PDF attachment, not as HTML pasted into the body.
 * Legacy inlines the whole rendered document; mail clients support a fraction
 * of that CSS, so it arrives looking different in each one and leaves the
 * customer nothing to keep. We already generate a real PDF — the same one the
 * screen downloads, marketing footer and line order included — so it is
 * attached and the body is three short lines.
 *
 * And those lines are real text. Legacy's email_quote_message and
 * email_quote_footer are untranslated placeholders in both of its language
 * files: english.json says "Email quote message" and espanol.json says "Piel
 * del correo", a typo for "Pie". There was nothing to carry over.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteEmailService {

    private final QuotationService quotationService;
    private final QuotePdfService quotePdfService;
    private final EmailSettingsService emailSettingsService;
    private final MailSenderFactory mailSenderFactory;
    private final MemberContactOverrideRepository overrideRepository;
    private final NativeQueries nativeQueries;
    private final MessageSource messageSource;

    /** Legacy's fallback when the sending user has no usable address. */
    private static final String DEFAULT_FROM = "noreply@pricesmart.com";
    private static final String FROM_NAME = "PriceSmart";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    /**
     * @param userId whose address the mail is sent from, so the member can
     *               reply to the advisor who quoted them. Legacy takes it from
     *               the session; we have no auth yet, so the screen passes it.
     */
    public String send(Long quotationId, Integer userId) {
        EmailSettings settings = emailSettingsService.load();
        if (!settings.enabled()) {
            throw new EmailNotSentException("Email sending is disabled by the administrator");
        }

        QuotationResponse quote = quotationService.getById(quotationId);
        String recipient = recipientFor(quote.getCustomerMembership());
        if (!isValid(recipient)) {
            throw new EmailNotSentException(
                    "The member has no valid email address on file" + (recipient.isBlank() ? "" : ": " + recipient));
        }

        String sender = senderFor(userId);
        Locale locale = ClubMarketingFooter.localeFor(quote.getStoreId() == null ? 0 : quote.getStoreId());

        byte[] pdf;
        try {
            pdf = quotePdfService.generate(quotationId);
        } catch (Exception e) {
            log.error("[QuoteEmailService] PDF failed for quotationId={}: {}", quotationId, e.getMessage());
            throw new EmailNotSentException("The quotation document could not be generated", e);
        }

        JavaMailSender mailSender = mailSenderFactory.create(settings);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(recipient);
            helper.setFrom(sender, FROM_NAME);
            // Legacy copies the sender only when EMAIL_SEND_COPY_TO_USER says
            // so, and only when their address is usable. It is false today.
            if (settings.copyToSender() && isValid(sender) && !sender.equals(DEFAULT_FROM)) {
                helper.setCc(sender);
            }
            helper.setSubject(msg("quote.email.subject", locale, quotationId));
            helper.setText(body(locale), true);
            helper.addAttachment("quote-" + quotationId + ".pdf",
                    new org.springframework.core.io.ByteArrayResource(pdf), "application/pdf");

            mailSender.send(message);
        } catch (Exception e) {
            log.error("[QuoteEmailService] send failed quotationId={} to={}: {}",
                    quotationId, recipient, e.getMessage());
            throw new EmailNotSentException("The email could not be sent", e);
        }

        log.info("[QuoteEmailService] sent quotationId={} to={} from={} bytes={}",
                quotationId, recipient, sender, pdf.length);
        return recipient;
    }

    // ── Addresses ─────────────────────────────────────────────────────────────

    /**
     * The staff correction first, then ps_socios — which is where legacy's
     * getsocioemail reads, and the same order MemberService applies.
     *
     * Deliberately not through MemberService.getMember: that calls the Business
     * API, and an outbound HTTP round trip inside a send turns a working relay
     * into a failed email whenever that service is slow.
     */
    private String recipientFor(String membership) {
        if (membership == null || membership.isBlank()) return "";

        Optional<String> override = overrideRepository.findByMembershipNumber(membership)
                .map(com.dqs.api.model.MemberContactOverride::getEmail);
        if (override.isPresent() && override.get() != null) {
            return override.get().trim();
        }

        return nativeQueries
                .scalar("SELECT email FROM ps_socios WHERE membership = ?", String.class, membership)
                .map(String::trim)
                .orElse("");
    }

    private String senderFor(Integer userId) {
        if (userId == null) return DEFAULT_FROM;
        return nativeQueries
                .scalar("SELECT email FROM users WHERE id = ?", String.class, userId)
                .map(String::trim)
                .filter(QuoteEmailService::isValid)
                .orElse(DEFAULT_FROM);
    }

    private static boolean isValid(String email) {
        return email != null && EMAIL.matcher(email).matches();
    }

    // ── Wording ───────────────────────────────────────────────────────────────

    private String body(Locale locale) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#222;\">"
             + "<p>" + esc(msg("quote.email.greeting", locale)) + "</p>"
             + "<p>" + esc(msg("quote.email.body", locale)) + "</p>"
             + "<p style=\"font-size:12px;color:#666;\">" + esc(msg("quote.email.footer", locale)) + "</p>"
             + "<p style=\"font-size:12px;color:#666;\">PriceSmart &#169; "
             + java.time.Year.now().getValue() + "</p>"
             + "</div>";
    }

    /** A missing key costs one line of the body, not the whole email. */
    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, "", locale);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
