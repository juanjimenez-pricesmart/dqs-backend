package com.dqs.api.service;

import com.dqs.api.dto.QuotationDocumentResponse;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.exception.VoucherUploadException;
import com.dqs.api.model.DocumentType;
import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationDocument;
import com.dqs.api.repository.DocumentTypeRepository;
import com.dqs.api.repository.QuotationDocumentRepository;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payment voucher.
 *
 * Nothing here reaches AWS: the S3 client is a mock, and what is asserted is
 * the request it was handed — the bucket, the key, and that a file which should
 * never have left the browser is refused before any of it is built.
 *
 * The key matters beyond tidiness. Legacy and QuoteCenter will write vouchers
 * side by side for as long as both run, so the layout has to be the one legacy
 * already uses, and a second file must not land on the first.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationDocumentServiceTest {

    @Mock private QuotationRepository quotationRepository;
    @Mock private QuotationDocumentRepository documentRepository;
    @Mock private DocumentTypeRepository documentTypeRepository;
    @Mock private S3Client s3Client;
    @Mock private NativeQueries nativeQueries;

    private static final DocumentType PROOF = DocumentType.builder()
            .id(4).code(DocumentType.PROOF_OF_PAYMENT).name("Proof of payment").build();

    /** The real bundles, so a refusal that loses its Spanish fails here. */
    private static final MessageSource MESSAGES = messageSource();

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private QuotationDocumentService service(Optional<S3Client> client) {
        QuotationDocumentService s = new QuotationDocumentService(
                quotationRepository, documentRepository, documentTypeRepository, nativeQueries,
                client, MESSAGES);
        ReflectionTestUtils.setField(s, "bucket", "dqs-quotes-dev");
        ReflectionTestUtils.setField(s, "region", "us-east-1");
        ReflectionTestUtils.setField(s, "maxBytes", 4L * 1024 * 1024);
        return s;
    }

    private QuotationDocumentService service() {
        return service(Optional.of(s3Client));
    }

    private MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "%PDF-1.4".getBytes());
    }

    @BeforeEach
    void wire() {
        // 5836 is a real user in dev; the screen's auth placeholder is 1, which
        // is not — see uploaderFor.
        when(nativeQueries.scalar(contains("FROM users"), eq(Long.class), eq(5836)))
                .thenReturn(Optional.of(1L));
        when(nativeQueries.scalar(contains("FROM users"), eq(Long.class), eq(1)))
                .thenReturn(Optional.of(0L));
        // A real S3Utilities, not a mock: the point of the change under test is
        // that the SDK knows how to address a bucket whose name contains dots,
        // and a stubbed URL would assert nothing about that.
        when(s3Client.utilities()).thenReturn(
                software.amazon.awssdk.services.s3.S3Utilities.builder()
                        .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                        .build());
        when(quotationRepository.findById(107L))
                .thenReturn(Optional.of(Quotation.builder().id(107L).storeId(6401).userId(1).build()));
        when(quotationTypePresent()).thenReturn(Optional.of(PROOF));
        when(documentRepository.save(any(QuotationDocument.class))).thenAnswer(i -> {
            QuotationDocument d = i.getArgument(0);
            d.setId(900L);
            return d;
        });
    }

    private Optional<DocumentType> quotationTypePresent() {
        return documentTypeRepository.findByCode(DocumentType.PROOF_OF_PAYMENT);
    }

    // ── The happy path, and the key ───────────────────────────────────────

    @Test
    @DisplayName("the voucher lands where legacy puts it, so both applications' files sit together")
    void theKeyMatchesLegacysLayout() {
        QuotationDocumentResponse response = service().uploadVoucher(107L, pdf("recibo.pdf"), 5836);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("dqs-quotes-dev");
        assertThat(put.getValue().key()).isEqualTo("quotes/order_107/quote_107_payment_voucher.pdf");

        // Built by the SDK, so a plain bucket name gets virtual-host addressing.
        assertThat(response.getStorageUrl())
                .isEqualTo("https://dqs-quotes-dev.s3.amazonaws.com/quotes/order_107/quote_107_payment_voucher.pdf");
        assertThat(response.getFileName()).isEqualTo("recibo.pdf");
        assertThat(response.getUploadedByUserId()).isEqualTo(5836);
    }

    @Test
    @DisplayName("a second voucher is suffixed rather than written over the first")
    void asecondVoucherDoesNotOverwriteTheFirst() {
        when(documentRepository.countByQuotation_IdAndDocumentType_Code(107L, DocumentType.PROOF_OF_PAYMENT))
                .thenReturn(1L);

        service().uploadVoucher(107L, pdf("segundo.pdf"), null);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        // Legacy indexes within one request, so two separate uploads collide
        // there. This counts what the quotation already has.
        assertThat(put.getValue().key()).isEqualTo("quotes/order_107/quote_107_payment_voucher_2.pdf");
    }

    @Test
    @DisplayName("the extension follows the file, so a JPG is not stored as a PDF")
    void theExtensionFollowsTheFile() {
        service().uploadVoucher(107L,
                new MockMultipartFile("file", "foto.JPG", "image/jpeg", "ÿØÿ".getBytes()), null);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).endsWith("_payment_voucher.jpg");
    }

    // ── What never reaches S3 ─────────────────────────────────────────────

    @Test
    @DisplayName("a file type the voucher does not accept is refused before the upload")
    void aWrongTypeIsRefused() {
        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "malo.exe", "application/x-msdownload", "MZ".getBytes()), null))
                .isInstanceOf(VoucherUploadException.class);

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a PDF content type on a .exe is still refused")
    void aLiedAboutContentTypeIsRefused() {
        // The browser derives the content type from the extension, so this only
        // happens in a request made by hand. Legacy checks the type it is
        // handed and nothing else.
        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "malo.exe", "application/pdf", "MZ".getBytes()), null))
                .isInstanceOf(VoucherUploadException.class);

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("a file over the 4Mb limit is refused before the upload")
    void anOversizedFileIsRefused() {
        byte[] big = new byte[5 * 1024 * 1024];
        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "grande.pdf", "application/pdf", big), null))
                .isInstanceOf(VoucherUploadException.class)
                .hasMessageContaining("4");

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("an empty file is refused")
    void anEmptyFileIsRefused() {
        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "vacio.pdf", "application/pdf", new byte[0]), null))
                .isInstanceOf(VoucherUploadException.class);
    }

    @Test
    @DisplayName("with storage switched off the voucher is still recorded, with no URL")
    void storageOffStillRecordsTheVoucher() {
        // ENABLE_AWS_S3 is off in legacy's production environment with no date
        // to enable it, and legacy accepts the file anyway — its controller
        // reports `aws_disabled` to the browser as a success. Refusing here
        // would be worse than legacy, not better: the close gate counts voucher
        // rows, so no row means no sale can ever be closed.
        QuotationDocumentResponse response =
                service(Optional.empty()).uploadVoucher(107L, pdf("recibo.pdf"), null);

        assertThat(response.getStorageUrl()).isNull();
        assertThat(response.getFileName()).isEqualTo("recibo.pdf");
        verify(documentRepository).save(any(QuotationDocument.class));
    }

    @Test
    @DisplayName("an S3 failure records the voucher too, rather than blocking the sale")
    void anS3FailureStillRecordsTheVoucher() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("access denied").build());

        QuotationDocumentResponse response = service().uploadVoucher(107L, pdf("recibo.pdf"), null);

        // Logged at ERROR, because an unreachable bucket is not expected the
        // way a disabled one is — but blocking a sale over it is not a trade
        // this screen gets to make.
        assertThat(response.getStorageUrl()).isNull();
        verify(documentRepository).save(any(QuotationDocument.class));
    }

    @Test
    @DisplayName("the file is validated even when nothing will be stored")
    void validationStillAppliesWithoutStorage() {
        // Otherwise the day storage is enabled we start keeping whatever was
        // waved through in the meantime.
        assertThatThrownBy(() -> service(Optional.empty()).uploadVoucher(107L,
                new MockMultipartFile("file", "malo.exe", "application/x-msdownload", "MZ".getBytes()), null))
                .isInstanceOf(VoucherUploadException.class);

        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a voucher with no URL still satisfies the close gate")
    void aVoucherWithNoUrlStillCounts() {
        when(documentRepository.countByQuotation_IdAndDocumentType_Code(107L, DocumentType.PROOF_OF_PAYMENT))
                .thenReturn(1L);

        // hasVoucher counts rows, not stored objects — which is the whole point
        // of writing the row when storage is off.
        assertThat(service(Optional.empty()).hasVoucher(107L)).isTrue();
    }

    @Test
    @DisplayName("a quotation that does not exist is a 404 and never touches S3")
    void anUnknownQuotationIsNotFound() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().uploadVoucher(404L, pdf("recibo.pdf"), null))
                .isInstanceOf(QuotationNotFoundException.class);

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // ── What the close gate asks ──────────────────────────────────────────

    @Test
    @DisplayName("hasVoucher answers from the database, not from the browser's file input")
    void hasVoucherAnswersFromStoredRows() {
        when(documentRepository.countByQuotation_IdAndDocumentType_Code(107L, DocumentType.PROOF_OF_PAYMENT))
                .thenReturn(1L);
        assertThat(service().hasVoucher(107L)).isTrue();

        when(documentRepository.countByQuotation_IdAndDocumentType_Code(108L, DocumentType.PROOF_OF_PAYMENT))
                .thenReturn(0L);
        // Legacy asks the file input, which is empty whenever the quotation is
        // reopened — so it demands the voucher again on every visit.
        assertThat(service().hasVoucher(108L)).isFalse();
    }

    @Test
    @DisplayName("listing a quotation's vouchers leaves out the other document types")
    void listingIsScopedToVouchers() {
        when(quotationRepository.existsById(107L)).thenReturn(true);
        when(documentRepository.findByQuotation_IdAndDocumentType_CodeOrderByIdAsc(
                107L, DocumentType.PROOF_OF_PAYMENT))
                .thenReturn(List.of(QuotationDocument.builder()
                        .id(900L).documentType(PROOF).fileName("recibo.pdf")
                        .storageUrl("https://example/x.pdf").build()));

        List<QuotationDocumentResponse> vouchers = service().listVouchers(107L);

        assertThat(vouchers).hasSize(1);
        assertThat(vouchers.get(0).getDocumentType()).isEqualTo(DocumentType.PROOF_OF_PAYMENT);
    }

    @Test
    @DisplayName("listing for a quotation that does not exist is a 404, not an empty list")
    void listingAnUnknownQuotationIsNotFound() {
        when(quotationRepository.existsById(404L)).thenReturn(false);

        assertThatThrownBy(() -> service().listVouchers(404L))
                .isInstanceOf(QuotationNotFoundException.class);
    }

    // ── The refusal reaches the operator in their own language ────────────

    @Test
    @DisplayName("a Spanish club is refused in Spanish")
    void aSpanishClubIsRefusedInSpanish() {
        // 6401 is Costa Rica. The reported bug was an operator in a Spanish
        // club reading "Unsupported Media Type" off the screen.
        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "malo.exe", "application/x-msdownload", "MZ".getBytes()), null))
                .hasMessageContaining("Solo se pueden adjuntar");

        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "vacio.pdf", "application/pdf", new byte[0]), null))
                .hasMessageContaining("ningún archivo");
    }

    @Test
    @DisplayName("an English club is refused in English")
    void anEnglishClubIsRefusedInEnglish() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(
                Quotation.builder().id(107L).storeId(8501).userId(1).build()));  // Barbados

        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "bad.exe", "application/x-msdownload", "MZ".getBytes()), null))
                .hasMessageContaining("Only JPG");
    }

    @Test
    @DisplayName("the 4Mb rule is ours to enforce, not the container's to pre-empt")
    void theSizeRuleIsOursToEnforce() {
        // spring.servlet.multipart.max-file-size sits deliberately above this
        // one. When the two matched, a 5Mb file was rejected by the container
        // before this method ran and answered a bare English 413, so the
        // localized message below could never be shown — and a phone photo is
        // the common way an operator goes over.
        byte[] justOver = new byte[4 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "foto.png", "image/png", justOver), null))
                .isInstanceOf(VoucherUploadException.class)
                .hasMessageContaining("4Mb");

        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("the size limit is named in the message, in either language")
    void theSizeLimitIsNamed() {
        byte[] big = new byte[5 * 1024 * 1024];
        assertThatThrownBy(() -> service().uploadVoucher(107L,
                new MockMultipartFile("file", "grande.pdf", "application/pdf", big), null))
                .hasMessageContaining("4Mb");
    }

    @Test
    @DisplayName("the stored URL is built by the SDK, so a dotted bucket name still resolves")
    void theStoredUrlIsBuiltBySdk() {
        // The configured AWS_BUCKET really is dqs-quotes-dev.s3.pricesmart.com,
        // a bucket named after a custom domain. Concatenating
        // ".s3.{region}.amazonaws.com" onto that produced
        // dqs-quotes-dev.s3.pricesmart.com.s3.us-east-1.amazonaws.com, which
        // opened nowhere — and every voucher row stored it.
        QuotationDocumentService s = service();
        ReflectionTestUtils.setField(s, "bucket", "dqs-quotes-dev.s3.pricesmart.com");

        String url = s.uploadVoucher(107L, pdf("recibo.pdf"), null).getStorageUrl();

        assertThat(url).doesNotContain(".s3.pricesmart.com.s3.");
        assertThat(url).contains("dqs-quotes-dev.s3.pricesmart.com");
        assertThat(url).endsWith("quotes/order_107/quote_107_payment_voucher.pdf");
        // A dotted bucket cannot use virtual-host addressing without breaking
        // TLS, so the SDK falls back to path style. That is the whole reason
        // this is not a string concatenation any more.
        assertThat(url).startsWith("https://s3.amazonaws.com/");
    }

    @Test
    @DisplayName("an unknown uploader is recorded as nobody, not as a failed upload")
    void anUnknownUploaderIsRecordedAsNobody() {
        // quotation_documents is the first table of ours with a real foreign
        // key to users, and the screen sends CreateQuotePage's auth placeholder
        // — `const USER_ID = 1`, and there is no user 1. Every upload from the
        // screen failed the constraint and answered 500, which the operator saw
        // as a bare "no se pudo adjuntar".
        QuotationDocumentResponse response = service().uploadVoucher(107L, pdf("recibo.pdf"), 1);

        assertThat(response.getUploadedByUserId()).isNull();
        assertThat(response.getFileName()).isEqualTo("recibo.pdf");
    }

    @Test
    @DisplayName("a real uploader is kept")
    void aRealUploaderIsKept() {
        assertThat(service().uploadVoucher(107L, pdf("recibo.pdf"), 5836).getUploadedByUserId())
                .isEqualTo(5836);
    }
}
