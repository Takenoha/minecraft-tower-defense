package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TeamProgressTest {
    @Test
    void initialProgressUnlocksTheFirstStage() {
        UUID teamId = UUID.randomUUID();

        TeamProgress progress = TeamProgress.initial(teamId);

        assertEquals(teamId, progress.teamId());
        assertEquals(0L, progress.highestClearedLevel());
        assertEquals(1L, progress.unlockedLevel());
        assertEquals(0L, progress.researchPoints());
    }

    @Test
    void victoryAdvancesHighestClearAndNextUnlockMonotonically() {
        UUID teamId = UUID.randomUUID();
        TeamProgress progress = new TeamProgress(teamId, 3L, 4L, 12L);

        assertEquals(
                new TeamProgress(teamId, 5L, 6L, 12L),
                progress.afterVictory(5L));
        assertEquals(
                progress,
                progress.afterVictory(2L));
    }
}
