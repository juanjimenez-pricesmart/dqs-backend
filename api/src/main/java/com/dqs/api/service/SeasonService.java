package com.dqs.api.service;

import com.dqs.api.repository.support.NativeQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Temporadas — the campaign a quotation belongs to.
 *
 * `ps_temporada` and `ps_temporada_tienda` belong to the application we are
 * replacing, so they are read through NativeQueries and never mapped. A season
 * is four fields and a set of clubs; status 2 is the active one, and in dev
 * exactly one of the eight rows carries it.
 *
 * The tag is only a tag. Nothing in legacy reads orders.temporada_id back —
 * `git grep temporada_id` over the whole application finds the write, the
 * read-back into this select, and nothing else. No pricing, no totals, no PDF,
 * no OMS, no report. Worth knowing before anyone builds on it.
 *
 * One deliberate difference: this filters by club. Legacy's get_temporadas is
 * `SELECT * FROM ps_temporada WHERE status = 2` with no club condition at all,
 * even though ps_temporada_tienda exists, is populated, and assigns today's
 * active season to two clubs out of sixty-two. Every other club is offered a
 * season it is not part of. Agreed with the user to filter; a club with no
 * seasons assigned gets an empty list rather than somebody else's campaign.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonService {

    private final NativeQueries nativeQueries;

    /** Legacy's status 2. The others are drafts and finished campaigns. */
    private static final int ACTIVE = 2;

    private static final String ACTIVE_SEASONS_FOR_CLUB = """
        SELECT t.tid, t.tag, t.titulo, t.descripcion
          FROM ps_temporada t
          JOIN ps_temporada_tienda tt ON tt.tid = t.tid
         WHERE t.status = ?
           AND tt.ps_tienda_id = ?
         ORDER BY t.tid
        """;

    /**
     * The seasons a club may tag a quotation with, in legacy's order.
     *
     * Empty when the club has none assigned, which is a real answer: the screen
     * shows the select with only its placeholder.
     */
    public List<Map<String, Object>> getActiveForClub(Integer clubId) {
        if (clubId == null) return List.of();
        List<Map<String, Object>> rows = nativeQueries.list(ACTIVE_SEASONS_FOR_CLUB, ACTIVE, clubId);
        log.debug("[SeasonService] clubId={} activeSeasons={}", clubId, rows.size());
        return rows;
    }

    /**
     * Whether a club may use a season. The screen filters the list already, so
     * this only catches a stale page or a request made by hand — the same
     * reason the gift card's amount is checked against its own list.
     */
    public boolean isAvailableForClub(Integer seasonId, Integer clubId) {
        if (seasonId == null || clubId == null) return false;
        return nativeQueries.scalar(
                "SELECT COUNT(*) FROM ps_temporada t JOIN ps_temporada_tienda tt ON tt.tid = t.tid"
                + " WHERE t.tid = ? AND t.status = ? AND tt.ps_tienda_id = ?",
                Long.class, seasonId, ACTIVE, clubId)
            .orElse(0L) > 0;
    }
}
