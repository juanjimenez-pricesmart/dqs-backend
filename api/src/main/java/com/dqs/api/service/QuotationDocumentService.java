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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The payment voucher: the file an operator has to attach before a quotation
 * can be closed as a sale.
 *
 * Legacy uploads it from the browser to orders/upload_s3 at the moment the
 * quote is submitted (`copiars3()`, edit.php:6597), with the request made
 * synchronously — `async: false` — so the page blocks until S3 answers, and
 * with the upload's own errors logged to the console and otherwise ignored:
 * createquote() proceeds whether or not the file made it. Here the upload is
 * its own request, made when the operator picks the file, and a failure is a
 * failure. The close gate then asks whether a voucher exists rather than
 * whether one was selected in this browser session.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationDocumentService {

    private final QuotationRepository quotationRepository;
    private final QuotationDocumentRepository documentRepository;
    private final DocumentTypeRepository documentTypeRepository;

    /** Absent when quotecenter.s3.enabled is false — see S3Config. */
    private final Optional<S3Client> s3Client;

    @Value("${aws.bucket:}")
    private String bucket;

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${quotecenter.voucher.max-bytes:4194304}")
    private long maxBytes;

    /** Legacy's own list, from validateFileBeforeUpload (edit.php:6649). */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "application/pdf");

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "pdf");

    // ── Upload ────────────────────────────────────────────────────────────────

    @Transactional
    public QuotationDocumentResponse uploadVoucher(Long quotationId, MultipartFile file, Integer userId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));

        S3Client client = s3Client.orElseThrow(() -> new VoucherUploadException(
                "Attachment storage is not configured in this environment"));

        validate(file);

        DocumentType type = documentTypeRepository.findByCode(DocumentType.PROOF_OF_PAYMENT)
                .orElseThrow(() -> new IllegalStateException(
                        "document_types is missing " + DocumentType.PROOF_OF_PAYMENT));

        // Legacy's layout, kept so both applications' vouchers land together
        // while they run side by side: quotes/order_{id}/quote_{id}_payment_voucher
        // with an index from the second file on. Its index is per-request; ours
        // counts what the quotation already has, so uploading one file at a
        // time does not overwrite the first.
        long existing = documentRepository.countByQuotation_IdAndDocumentType_Code(
                quotationId, DocumentType.PROOF_OF_PAYMENT);
        String suffix = existing > 0 ? "_" + (existing + 1) : "";
        String key = "quotes/order_" + quotationId + "/quote_" + quotationId
                + "_payment_voucher" + suffix + "." + extensionOf(file);

        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromBytes(file.getBytes()));
        } catch (IOException | RuntimeException e) {
            log.error("[QuotationDocumentService] upload failed quotationId={} key={}: {}",
                    quotationId, key, e.getMessage());
            throw new VoucherUploadException("The file could not be stored", e);
        }

        QuotationDocument document = documentRepository.save(QuotationDocument.builder()
                .quotation(quotation)
                .documentType(type)
                .fileName(file.getOriginalFilename())
                .storageUrl(urlFor(key))
                .uploadedByUserId(userId)
                .build());

        log.info("[QuotationDocumentService] voucher stored quotationId={} key={} bytes={}",
                quotationId, key, file.getSize());
        return toResponse(document);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<QuotationDocumentResponse> listVouchers(Long quotationId) {
        if (!quotationRepository.existsById(quotationId)) {
            throw new QuotationNotFoundException(quotationId);
        }
        return documentRepository
                .findByQuotation_IdAndDocumentType_CodeOrderByIdAsc(quotationId, DocumentType.PROOF_OF_PAYMENT)
                .stream()
                .map(QuotationDocumentService::toResponse)
                .toList();
    }

    /**
     * What the close gate asks. Legacy asks the file input instead
     * (`attachmentFile == ''`, edit.php:2715), which answers for this browser
     * session only: reopening a quotation that already has a voucher shows an
     * empty input and the gate fires again.
     */
    public boolean hasVoucher(Long quotationId) {
        return documentRepository.countByQuotation_IdAndDocumentType_Code(
                quotationId, DocumentType.PROOF_OF_PAYMENT) > 0;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new VoucherUploadException("No file was uploaded");
        }
        if (file.getSize() > maxBytes) {
            throw new VoucherUploadException(
                    "The file is larger than the " + (maxBytes / 1024 / 1024) + "MB limit");
        }
        // Both are checked, not either: the browser sets the content type from
        // the extension anyway, and a request made by hand sets whatever it
        // likes. Legacy checks only the type it was handed.
        String contentType = file.getContentType() == null ? ""
                : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType) || !ALLOWED_EXTENSIONS.contains(extensionOf(file))) {
            throw new VoucherUploadException("Only JPG, GIF, PNG and PDF files can be attached");
        }
    }

    private String extensionOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Legacy stores `https://{bucket}/{key}`, which is not a resolvable address
     * — it drops the S3 host entirely, so nothing can open the link it saved.
     * This stores the real one.
     */
    private String urlFor(String key) {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
    }

    private static QuotationDocumentResponse toResponse(QuotationDocument document) {
        return QuotationDocumentResponse.builder()
                .id(document.getId())
                .quotationId(document.getQuotation() != null ? document.getQuotation().getId() : null)
                .documentType(document.getDocumentType() != null ? document.getDocumentType().getCode() : null)
                .fileName(document.getFileName())
                .storageUrl(document.getStorageUrl())
                .uploadedByUserId(document.getUploadedByUserId())
                .createdAt(document.getCreatedAt())
                .build();
    }
}
