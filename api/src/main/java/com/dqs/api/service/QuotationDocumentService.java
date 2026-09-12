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
import com.dqs.api.util.ClubMarketingFooter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
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
 * synchronously — `async: false` — and its errors logged to the console and
 * otherwise ignored: createquote() proceeds whether or not the file made it.
 * Here the upload is its own request, made when the operator picks the file,
 * and the close gate asks whether a voucher exists rather than whether one was
 * selected in this browser session.
 *
 * <h2>When storage is unavailable</h2>
 *
 * It usually is. ENABLE_AWS_S3 is off in legacy's production environment and
 * there is no date to enable it, so today the whole feature stores nothing
 * anywhere: AWSService answers `aws_disabled`, the controller reports that to
 * the browser as a success, and the quote closes.
 *
 * The first version of this refused the upload outright in that case, which
 * was worse than legacy rather than better — the close gate counts voucher
 * rows, so with storage off no row could be written and <em>no sale could ever
 * be closed</em>. The file is still validated, the row is still written, and
 * storage_url is left NULL. The flow behaves as legacy's does; unlike legacy,
 * afterwards we can tell which quotations were closed against a voucher whose
 * bytes nobody kept.
 *
 * A storage failure at runtime is treated the same way and logged at ERROR.
 * Blocking a sale because a bucket is unreachable is not a trade this screen
 * gets to make.
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

    private final MessageSource messageSource;

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

        // These messages end up on the operator's screen, so they follow the
        // club's language — the same bundle and the same resolution the PDF's
        // marketing footer and the quotation email use.
        Locale locale = ClubMarketingFooter.localeFor(
                quotation.getStoreId() == null ? 0 : quotation.getStoreId());

        validate(file, locale);

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

        String storageUrl = store(file, key, quotationId);

        QuotationDocument document = documentRepository.save(QuotationDocument.builder()
                .quotation(quotation)
                .documentType(type)
                .fileName(file.getOriginalFilename())
                .storageUrl(storageUrl)
                .uploadedByUserId(userId)
                .build());

        log.info("[QuotationDocumentService] voucher recorded quotationId={} key={} bytes={} stored={}",
                quotationId, key, file.getSize(), storageUrl != null);
        return toResponse(document);
    }

    /**
     * Puts the file in S3 and answers where it landed, or null when it did not.
     *
     * Never throws. The row is written either way, because refusing the
     * attachment would stop the sale — see the note on this class.
     */
    private String store(MultipartFile file, String key, Long quotationId) {
        if (s3Client.isEmpty()) {
            log.warn("[QuotationDocumentService] storage disabled, voucher accepted but not stored"
                    + " quotationId={} name={}", quotationId, file.getOriginalFilename());
            return null;
        }
        S3Client client = s3Client.get();
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromBytes(file.getBytes()));
            return urlFor(client, key);
        } catch (IOException | RuntimeException e) {
            // Loud, because this one is not expected the way a disabled bucket
            // is — but still not a reason to block the close.
            log.error("[QuotationDocumentService] storage failed, voucher accepted but not stored"
                    + " quotationId={} key={}: {}", quotationId, key, e.getMessage());
            return null;
        }
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

    private void validate(MultipartFile file, Locale locale) {
        if (file == null || file.isEmpty()) {
            throw new VoucherUploadException(msg("voucher.error.no-file", locale));
        }
        if (file.getSize() > maxBytes) {
            throw new VoucherUploadException(
                    msg("voucher.error.too-large", locale, maxBytes / 1024 / 1024));
        }
        // Both are checked, not either: the browser sets the content type from
        // the extension anyway, and a request made by hand sets whatever it
        // likes. Legacy checks only the type it was handed.
        String contentType = file.getContentType() == null ? ""
                : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType) || !ALLOWED_EXTENSIONS.contains(extensionOf(file))) {
            throw new VoucherUploadException(msg("voucher.error.wrong-type", locale));
        }
    }

    /** A missing key costs the sentence, not the refusal. */
    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, key, locale);
    }

    private String extensionOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Built by the SDK rather than by string concatenation.
     *
     * The first version here assembled `https://{bucket}.s3.{region}.amazonaws.com/{key}`
     * on the assumption that AWS_BUCKET is a plain bucket name. It is not: the
     * configured value is `dqs-quotes-dev.s3.pricesmart.com`, a bucket named
     * after a custom domain, so every stored URL came out as
     * `https://dqs-quotes-dev.s3.pricesmart.com.s3.us-east-1.amazonaws.com/...`
     * and opened nowhere.
     *
     * That also corrects something said about legacy when this was written:
     * its `https://{bucket}/{key}` was called unresolvable, and with this
     * bucket name it resolves perfectly well. S3Utilities handles both shapes —
     * a dotted bucket cannot use virtual-host addressing without breaking TLS,
     * and the SDK knows to fall back to path style.
     */
    private String urlFor(S3Client client, String key) {
        return client.utilities().getUrl(
                software.amazon.awssdk.services.s3.model.GetUrlRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build())
                .toExternalForm();
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
