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

    private static final DocumentType PROOF = DocumentType.builder()
            .id(4).code(DocumentType.PROOF_OF_PAYMENT).name("Proof of payment").build();

    private QuotationDocumentService service(Optional<S3Client> client) {
        QuotationDocumentService s = new QuotationDocumentService(
                quotationRepository, documentRepository, documentTypeRepository, client);
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
        QuotationDocumentResponse response = service().uploadVoucher(107L, pdf("recibo.pdf"), 5836L);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("dqs-quotes-dev");
        assertThat(put.getValue().key()).isEqualTo("quotes/order_107/quote_107_payment_voucher.pdf");

        // Legacy saves https://{bucket}/{key}, which has no S3 host in it and
        // therefore opens nowhere. This one resolves.
        assertThat(response.getStorageUrl())
                .isEqualTo("https://dqs-quotes-dev.s3.us-east-1.amazonaws.com/quotes/order_107/quote_107_payment_voucher.pdf");
        assertThat(response.getFileName()).isEqualTo("recibo.pdf");
        assertThat(response.getUploadedByUserId()).isEqualTo(5836L);
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
                .hasMessageContaining("4MB");

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
    @DisplayName("with storage switched off the upload is refused, not silently dropped")
    void storageOffRefusesTheUpload() {
        // The bean does not exist when quotecenter.s3.enabled is false, so the
        // service has to answer rather than fail at construction.
        assertThatThrownBy(() -> service(Optional.empty()).uploadVoucher(107L, pdf("recibo.pdf"), null))
                .isInstanceOf(VoucherUploadException.class)
                .hasMessageContaining("not configured");

        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("an S3 failure leaves no row claiming a file that is not there")
    void anS3FailureSavesNothing() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("access denied").build());

        assertThatThrownBy(() -> service().uploadVoucher(107L, pdf("recibo.pdf"), null))
                .isInstanceOf(VoucherUploadException.class);

        // A row pointing at a missing object is worse than no row: the close
        // gate would pass and the voucher would not exist.
        verify(documentRepository, never()).save(any());
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
}
