package com.dqs.api.service;

import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.exception.EmailNotSentException;
import com.dqs.api.model.MemberContactOverride;
import com.dqs.api.repository.MemberContactOverrideRepository;
import com.dqs.api.repository.support.NativeQueries;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Emailing the quotation.
 *
 * Nothing here opens a socket. The relay is internal to PriceSmart — port 25,
 * no authentication, unreachable from a developer machine — so the factory is
 * mocked and what is asserted is the message it was handed. That also means
 * this path cannot be verified end to end anywhere but a deployed environment,
 * which is the reason the assertions below are as specific as they are.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuoteEmailServiceTest {

    @Mock private QuotationService quotationService;
    @Mock private QuotePdfService quotePdfService;
    @Mock private EmailSettingsService emailSettingsService;
    @Mock private MailSenderFactory mailSenderFactory;
    @Mock private MemberContactOverrideRepository overrideRepository;
    @Mock private NativeQueries nativeQueries;
    @Mock private JavaMailSender mailSender;

    private static final MessageSource MESSAGES = messageSource();

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private QuoteEmailService service() {
        return new QuoteEmailService(quotationService, quotePdfService, emailSettingsService,
                mailSenderFactory, overrideRepository, nativeQueries, MESSAGES);
    }

    private EmailSettings settings(boolean enabled, boolean copyToSender) {
        return EmailSettings.builder()
                .enabled(enabled).host("smtp-app.int.pricesmart.com").port(25)
                .auth(false).user("").password("").copyToSender(copyToSender).build();
    }

    @BeforeEach
    void wire() throws Exception {
        when(emailSettingsService.load()).thenReturn(settings(true, false));
        when(quotationService.getById(107L)).thenReturn(QuotationResponse.builder()
                .id(107L).storeId(6401).customerMembership("70012345678901").build());
        when(quotePdfService.generate(107L)).thenReturn("%PDF-1.4 fake".getBytes());
        when(overrideRepository.findByMembershipNumber(any())).thenReturn(Optional.empty());
        when(nativeQueries.scalar(contains("ps_socios"), eq(String.class), any()))
                .thenReturn(Optional.of("socio@example.com"));
        when(nativeQueries.scalar(contains("FROM users"), eq(String.class), any()))
                .thenReturn(Optional.of("asesor@pricesmart.com"));
        when(mailSenderFactory.create(any())).thenReturn(mailSender);
        when(mailSender.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    // ── The message ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the quotation goes to the member, from the advisor, with the PDF attached")
    void theMessageCarriesThePdf() throws Exception {
        assertThat(service().send(107L, 5836)).isEqualTo("socio@example.com");

        MimeMessage message = sentMessage();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("socio@example.com");
        assertThat(message.getFrom()[0].toString()).contains("asesor@pricesmart.com");
        // Legacy pastes the rendered document into the body instead, which mail
        // clients render differently and leave nothing to keep.
        assertThat(attachmentNames(message)).containsExactly("quote-107.pdf");
    }

    /** Walks the multipart tree collecting the parts that have a filename. */
    private static java.util.List<String> attachmentNames(jakarta.mail.Part part) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (part.getFileName() != null) names.add(part.getFileName());
        Object content = part.getContent();
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                names.addAll(attachmentNames(multipart.getBodyPart(i)));
            }
        }
        return names;
    }

    @Test
    @DisplayName("the subject and body are in the club's language")
    void theWordingFollowsTheClub() throws Exception {
        service().send(107L, 5836);
        // 6401 is Costa Rica, so Spanish — and the voseo bundle at that.
        assertThat(sentMessage().getSubject()).contains("Cotización");

        when(quotationService.getById(107L)).thenReturn(QuotationResponse.builder()
                .id(107L).storeId(8501).customerMembership("70012345678901").build());
        QuoteEmailService english = service();
        english.send(107L, 5836);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(1).getSubject()).contains("Quote");
    }

    @Test
    @DisplayName("the staff correction wins over ps_socios, as everywhere else")
    void theOverrideWinsOverPsSocios() {
        when(overrideRepository.findByMembershipNumber("70012345678901"))
                .thenReturn(Optional.of(MemberContactOverride.builder().email("corregido@example.com").build()));

        assertThat(service().send(107L, 5836)).isEqualTo("corregido@example.com");
        // The Business API is never called: an outbound round trip inside a
        // send turns a slow service into a failed email.
        verify(nativeQueries, never()).scalar(contains("ps_socios"), any(), any());
    }

    @Test
    @DisplayName("a sender with no address on file falls back to noreply")
    void anUnknownSenderFallsBackToNoreply() throws Exception {
        when(nativeQueries.scalar(contains("FROM users"), eq(String.class), any()))
                .thenReturn(Optional.empty());

        service().send(107L, 5836);

        assertThat(sentMessage().getFrom()[0].toString()).contains("noreply@pricesmart.com");
    }

    @Test
    @DisplayName("no userId at all still sends, from noreply")
    void noUserIdStillSends() throws Exception {
        service().send(107L, null);
        assertThat(sentMessage().getFrom()[0].toString()).contains("noreply@pricesmart.com");
    }

    @Test
    @DisplayName("the sender is copied only when the configuration says so")
    void theSenderIsCopiedOnlyWhenConfigured() throws Exception {
        assertThat(sentAfterSendWithCopy(false).getRecipients(MimeMessage.RecipientType.CC)).isNull();

        assertThat(sentAfterSendWithCopy(true).getRecipients(MimeMessage.RecipientType.CC)[0].toString())
                .isEqualTo("asesor@pricesmart.com");
    }

    private MimeMessage sentAfterSendWithCopy(boolean copy) {
        org.mockito.Mockito.reset(mailSender);
        when(mailSender.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        when(emailSettingsService.load()).thenReturn(settings(true, copy));
        service().send(107L, 5836);
        return sentMessage();
    }

    // ── What does not send ────────────────────────────────────────────────

    @Test
    @DisplayName("with sending switched off nothing is built and nothing goes out")
    void disabledSendsNothing() throws Exception {
        when(emailSettingsService.load()).thenReturn(settings(false, false));

        assertThatThrownBy(() -> service().send(107L, 5836))
                .isInstanceOf(EmailNotSentException.class)
                .hasMessageContaining("disabled");

        // EMAIL_ENABLED is the administrator's kill switch; it is read on every
        // send so it takes effect without a restart.
        verify(quotePdfService, never()).generate(anyLong());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("a member with no address, or a malformed one, is refused before the PDF is built")
    void aMemberWithoutAnAddressIsRefused() throws Exception {
        for (String address : new String[] { "", "   ", "no-es-un-correo", "sin@dominio" }) {
            when(nativeQueries.scalar(contains("ps_socios"), eq(String.class), any()))
                    .thenReturn(Optional.of(address));

            assertThatThrownBy(() -> service().send(107L, 5836))
                    .isInstanceOf(EmailNotSentException.class);
        }
        verify(quotePdfService, never()).generate(anyLong());
    }

    @Test
    @DisplayName("a PDF that cannot be built stops the send rather than mailing an empty attachment")
    void aFailedPdfStopsTheSend() throws Exception {
        when(quotePdfService.generate(107L)).thenThrow(new RuntimeException("no template"));

        assertThatThrownBy(() -> service().send(107L, 5836))
                .isInstanceOf(EmailNotSentException.class)
                .hasMessageContaining("document");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("a relay that refuses the message is reported, not swallowed")
    void aRefusedMessageIsReported() {
        doThrow(new MailSendException("relay said no")).when(mailSender).send(any(MimeMessage.class));

        // Legacy opens a popup and closes it after three seconds unless the
        // page happens to contain the word "error", so a refusal is invisible.
        assertThatThrownBy(() -> service().send(107L, 5836))
                .isInstanceOf(EmailNotSentException.class);
    }
}
