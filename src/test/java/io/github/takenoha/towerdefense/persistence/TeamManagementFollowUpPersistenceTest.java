package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TeamManagementFollowUpPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T05:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void teamRenameIsDurableAndUuidIdempotent() {
        Path databaseFile = temporaryDirectory.resolve("named-team.sqlite");
        DefenseRepository repository = new DefenseRepository(new Database(databaseFile));
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);

        UUID operationId = UUID.randomUUID();
        TeamMutationResult renamed = repository.renameTeam(
                teamId, ownerId, "  星の防衛隊  ", operationId, NOW.plusSeconds(1L));

        assertEquals(ManagementOutcome.APPLIED, renamed.outcome());
        assertEquals("星の防衛隊", renamed.team().orElseThrow().displayName());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.renameTeam(
                        teamId,
                        ownerId,
                        "星の防衛隊",
                        operationId,
                        NOW.plusSeconds(2L)).outcome());
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.renameTeam(
                        teamId,
                        UUID.randomUUID(),
                        "不正変更",
                        UUID.randomUUID(),
                        NOW));

        DefenseRepository reopened = new DefenseRepository(new Database(databaseFile));
        assertEquals("星の防衛隊", reopened.findTeam(teamId).orElseThrow().displayName());
    }

    @Test
    void offlineInvitationCanBeAcceptedAfterReconnectAndRetried() {
        Path databaseFile = temporaryDirectory.resolve("offline-invite.sqlite");
        DefenseRepository repository = new DefenseRepository(new Database(databaseFile));
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID inviteeId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);

        UUID invitationId = UUID.randomUUID();
        UUID createOperation = UUID.randomUUID();
        TeamInvitationMutationResult created = repository.createTeamInvitation(
                teamId,
                ownerId,
                inviteeId,
                invitationId,
                createOperation,
                NOW,
                NOW.plusSeconds(600L));
        assertEquals(ManagementOutcome.APPLIED, created.outcome());
        assertEquals(TeamInvitationState.PENDING, created.invitation().state());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.createTeamInvitation(
                        teamId,
                        ownerId,
                        inviteeId,
                        invitationId,
                        createOperation,
                        NOW,
                        NOW.plusSeconds(600L)).outcome());
        assertEquals(1, repository.findPendingTeamInvitations(inviteeId, NOW).size());

        UUID acceptOperation = UUID.randomUUID();
        TeamInvitationMutationResult accepted = repository.acceptTeamInvitation(
                invitationId, inviteeId, acceptOperation, NOW.plusSeconds(1L));
        assertEquals(ManagementOutcome.APPLIED, accepted.outcome());
        assertEquals(TeamInvitationState.ACCEPTED, accepted.invitation().state());
        assertEquals(Set.of(ownerId, inviteeId), accepted.team().orElseThrow().members());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.acceptTeamInvitation(
                        invitationId, inviteeId, acceptOperation, NOW.plusSeconds(2L)).outcome());
        assertTrue(repository.findPendingTeamInvitations(inviteeId, NOW.plusSeconds(2L)).isEmpty());

        DefenseRepository reopened = new DefenseRepository(new Database(databaseFile));
        assertEquals(
                Set.of(ownerId, inviteeId),
                reopened.findTeam(teamId).orElseThrow().members());
        assertEquals(TeamInvitationState.ACCEPTED,
                reopened.findTeamInvitation(invitationId).orElseThrow().state());
    }

    @Test
    void invitationsExpireDeclineAndEnforceTheTeamMemberLimit() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("invite-boundaries.sqlite")));
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);

        UUID expiredInvite = UUID.randomUUID();
        UUID expiredPlayer = UUID.randomUUID();
        repository.createTeamInvitation(
                teamId,
                ownerId,
                expiredPlayer,
                expiredInvite,
                UUID.randomUUID(),
                NOW,
                NOW.plusSeconds(1L));
        assertTrue(repository.findPendingTeamInvitations(expiredPlayer, NOW.plusSeconds(1L)).isEmpty());
        assertEquals(
                TeamInvitationState.EXPIRED,
                repository.findTeamInvitation(expiredInvite).orElseThrow().state());

        UUID acceptExpiredInvite = UUID.randomUUID();
        UUID acceptExpiredPlayer = UUID.randomUUID();
        repository.createTeamInvitation(
                teamId,
                ownerId,
                acceptExpiredPlayer,
                acceptExpiredInvite,
                UUID.randomUUID(),
                NOW,
                NOW.plusSeconds(1L));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.acceptTeamInvitation(
                        acceptExpiredInvite,
                        acceptExpiredPlayer,
                        UUID.randomUUID(),
                        NOW.plusSeconds(1L)));
        assertEquals(
                TeamInvitationState.EXPIRED,
                repository.findTeamInvitation(acceptExpiredInvite).orElseThrow().state());

        UUID declinedInvite = UUID.randomUUID();
        UUID declinedPlayer = UUID.randomUUID();
        repository.createTeamInvitation(
                teamId,
                ownerId,
                declinedPlayer,
                declinedInvite,
                UUID.randomUUID(),
                NOW,
                NOW.plusSeconds(600L));
        TeamInvitationMutationResult declined = repository.declineTeamInvitation(
                declinedInvite, declinedPlayer, UUID.randomUUID(), NOW.plusSeconds(2L));
        assertEquals(TeamInvitationState.DECLINED, declined.invitation().state());

        for (int index = 0; index < DefenseRepository.MAX_TEAM_MEMBERS - 1; index++) {
            repository.addTeamMember(
                    teamId,
                    ownerId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    NOW.plusSeconds(10L + index));
        }
        assertEquals(
                DefenseRepository.MAX_TEAM_MEMBERS,
                repository.findTeam(teamId).orElseThrow().members().size());
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.addTeamMember(
                        teamId,
                        ownerId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        NOW.plusSeconds(100L)));
        assertTrue(repository.findPendingTeamInvitations(declinedPlayer, NOW).isEmpty());
    }
}
