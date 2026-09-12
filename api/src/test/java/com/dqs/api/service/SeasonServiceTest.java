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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Temporadas.
 *
 * The one thing worth defending is the club filter, because it is the single
 * place this diverges from legacy. Legacy's get_temporadas is
 * `SELECT * FROM ps_temporada WHERE status = 2` with no club condition, so in
 * dev today sixty of sixty-two clubs are offered "Gift Program FY25", a
 * campaign assigned to two Colombian clubs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeasonServiceTest {

    @Mock private NativeQueries nativeQueries;

    private SeasonService service() {
        return new SeasonService(nativeQueries);
    }

    private Map<String, Object> season(int tid, String title) {
        return Map.of("tid", tid, "tag", title, "titulo", title, "descripcion", title);
    }

    @Test
    @DisplayName("a club is offered only the active seasons assigned to it")
    void onlyTheClubsOwnSeasons() {
        when(nativeQueries.list(contains("ps_temporada_tienda"), eq(2), eq(6101)))
                .thenReturn(List.of(season(12, "Gift Program FY25")));

        List<Map<String, Object>> seasons = service().getActiveForClub(6101);

        assertThat(seasons).hasSize(1);
        assertThat(seasons.get(0).get("titulo")).isEqualTo("Gift Program FY25");
        // status 2 is the active one; the other seven rows in dev are drafts
        // and finished campaigns and must not appear.
        verify(nativeQueries).list(contains("t.status = ?"), eq(2), eq(6101));
    }

    @Test
    @DisplayName("a club with no seasons assigned gets an empty list, not somebody else's campaign")
    void aClubWithNoSeasonsGetsNothing() {
        when(nativeQueries.list(any(), any(), any())).thenReturn(List.of());

        assertThat(service().getActiveForClub(6301)).isEmpty();
    }

    @Test
    @DisplayName("no club means no query at all")
    void noClubIsAnsweredImmediately() {
        assertThat(service().getActiveForClub(null)).isEmpty();
        verify(nativeQueries, never()).list(any(), any(), any());
    }

    @Test
    @DisplayName("a season is available only when it is both active and the club's")
    void availabilityNeedsBoth() {
        when(nativeQueries.scalar(any(), eq(Long.class), eq(12), eq(2), eq(6101)))
                .thenReturn(Optional.of(1L));
        assertThat(service().isAvailableForClub(12, 6101)).isTrue();

        when(nativeQueries.scalar(any(), eq(Long.class), eq(12), eq(2), eq(6301)))
                .thenReturn(Optional.of(0L));
        // 6301 is Guatemala; the campaign belongs to two Colombian clubs.
        assertThat(service().isAvailableForClub(12, 6301)).isFalse();
    }

    @Test
    @DisplayName("a missing season or club is never available, and costs no query")
    void nullsAreNeverAvailable() {
        assertThat(service().isAvailableForClub(null, 6101)).isFalse();
        assertThat(service().isAvailableForClub(12, null)).isFalse();
        verify(nativeQueries, never()).scalar(any(), any(), any());
    }
}
