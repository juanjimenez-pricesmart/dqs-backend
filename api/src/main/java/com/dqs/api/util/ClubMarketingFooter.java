package com.dqs.api.util;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which clubs print the marketing footer on their quotation PDF, in which
 * language, and under which amount.
 *
 * Legacy has no such footer in the PDF itself: views/pdf/quote_footer.php
 * carries only the club disclaimer and the "Document generated" line. The
 * marketing block is assembled in the browser by imprimir()
 * (views/orders/edit.php:4283-4425), which opens the print popup and injects a
 * div into it once it loads. We render it server-side, so the PDF is the same
 * document however it was produced — downloaded, emailed or printed — rather
 * than only when it went through that one button.
 *
 * The club table below is legacy's getClubAndThreshold verbatim, resolution
 * order included, and deliberately does NOT reuse ClubCapabilities' ranges. The
 * two disagree, and here legacy wins, because a threshold is a commercial
 * decision taken per club:
 *
 *   - El Salvador is the explicit list {6501,6502,6701,6702,6703,6704}.
 *     ClubCapabilities has the range 6500-6599 instead, which would drop the
 *     four 67xx Callejas clubs off the footer entirely.
 *   - The Dominican Republic exists here and nowhere else in this backend.
 *   - Ecuador and Peru resolve to a club but to no threshold, so they print no
 *     footer. For Ecuador that is a side effect rather than a decision: EC and
 *     DO share 6800-6899 and EC is tested first, so every 68xx club outside the
 *     explicit Dominican list falls into Ecuador and loses the footer. It is
 *     what production does today. Changing it is a product call, not a port.
 *
 * Amounts are in the club's own currency, which is why they range from 500 to
 * 2,000,000.
 */
public final class ClubMarketingFooter {

    private ClubMarketingFooter() {}

    // ── Clubs addressed by id, ahead of any range ─────────────────────────────

    private static final Set<Integer> EL_SALVADOR_IDS = Set.of(6501, 6502, 6701, 6702, 6703, 6704);
    private static final Set<Integer> HONDURAS_IDS    = Set.of(6601, 6602, 6603, 6604);
    private static final Set<Integer> DOMINICAN_IDS   = Set.of(6801, 6802, 6804, 6805, 6806);

    private static final BigDecimal EL_SALVADOR_THRESHOLD = threshold(500);
    private static final BigDecimal HONDURAS_THRESHOLD    = threshold(13000);
    private static final BigDecimal DOMINICAN_THRESHOLD   = threshold(32500);

    // ── Clubs addressed by range, first match wins ────────────────────────────

    private record Range(int min, int max, String iso, BigDecimal threshold) {}

    private static final List<Range> RANGES = List.of(
        new Range(6100, 6199, "CO", threshold(2000000)),
        new Range(6200, 6299, "PA", threshold(500)),
        new Range(6300, 6399, "GT", threshold(3750)),
        new Range(6400, 6499, "CR", threshold(250000)),
        new Range(6800, 6899, "EC", null),              // ahead of DO, and with no threshold — see above
        new Range(6800, 6899, "DO", DOMINICAN_THRESHOLD),
        new Range(8000, 8099, "TT", threshold(3500)),
        new Range(8100, 8199, "VI", threshold(500)),
        new Range(8200, 8299, "AW", threshold(900)),
        new Range(8500, 8599, "BB", threshold(1000)),
        new Range(8700, 8799, "JM", threshold(80000)),
        new Range(8900, 8999, "NI", threshold(18500)),
        new Range(9000, 9099, "PE", null)               // no threshold either
    );

    /** The clubs whose quotations read in Spanish. Everything else reads in English. */
    private static final Set<String> SPANISH = Set.of("CO", "PA", "GT", "CR", "SV", "HN", "NI", "DO", "EC", "PE");

    private static final Locale SPANISH_LOCALE = Locale.forLanguageTag("es");
    private static final Locale COSTA_RICA     = Locale.forLanguageTag("es-CR");
    private static final Locale NICARAGUA      = Locale.forLanguageTag("es-NI");

    // ── Resolution ────────────────────────────────────────────────────────────

    private record Club(String iso, BigDecimal threshold) {}

    private static Club resolve(int storeId) {
        if (storeId <= 0) return null;
        if (EL_SALVADOR_IDS.contains(storeId)) return new Club("SV", EL_SALVADOR_THRESHOLD);
        if (HONDURAS_IDS.contains(storeId))    return new Club("HN", HONDURAS_THRESHOLD);
        if (DOMINICAN_IDS.contains(storeId))   return new Club("DO", DOMINICAN_THRESHOLD);
        for (Range range : RANGES) {
            if (storeId >= range.min() && storeId <= range.max()) {
                return new Club(range.iso(), range.threshold());
            }
        }
        return null;
    }

    /**
     * Whether this quotation prints the footer.
     *
     * The footer is an invitation to buy online instead, so it is shown to the
     * small quotes and withheld from the large ones. Legacy compares with
     * {@code <=} against the figure in the edit screen's #net_amount box, which
     * despite the name holds response.total — the final total, tax and delivery
     * included, the same number the PDF prints as Total / Compra Total.
     */
    public static boolean printsFooter(int storeId, BigDecimal quoteTotal) {
        Club club = resolve(storeId);
        if (club == null || club.threshold() == null || quoteTotal == null) return false;
        return quoteTotal.compareTo(club.threshold()) <= 0;
    }

    /**
     * The language the footer is written in, and for Costa Rica and Nicaragua
     * the voseo wording legacy keeps under its _vo phrase keys. Legacy picks
     * voseo off the store id (6400-6499, 8900-8999), not off the country
     * column, so this does too.
     */
    public static Locale localeFor(int storeId) {
        Club club = resolve(storeId);
        if (club == null || !SPANISH.contains(club.iso())) return Locale.ENGLISH;
        if (ClubCapabilities.isCostaRica(storeId)) return COSTA_RICA;
        if (ClubCapabilities.isNicaragua(storeId)) return NICARAGUA;
        return SPANISH_LOCALE;
    }

    private static BigDecimal threshold(int amount) {
        return BigDecimal.valueOf(amount);
    }
}
