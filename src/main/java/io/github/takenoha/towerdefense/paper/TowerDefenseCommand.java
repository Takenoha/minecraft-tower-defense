package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.domain.CombatArea;
import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import io.github.takenoha.towerdefense.domain.StageWaveSchedule;
import io.github.takenoha.towerdefense.domain.WaveMutation;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.StartOutcome;
import io.github.takenoha.towerdefense.persistence.StartRequest;
import io.github.takenoha.towerdefense.persistence.TeamInvitation;
import io.github.takenoha.towerdefense.persistence.TeamInvitationMutationResult;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import io.github.takenoha.towerdefense.persistence.TacticalBuildRepository;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseRuntimeStatus;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import io.github.takenoha.towerdefense.runtime.TerrainMutationActivationGate;
import io.github.takenoha.towerdefense.tactical.TacticalTerminalResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Administrator-only entry point for the first walking-skeleton milestone. */
public final class TowerDefenseCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN_PERMISSION = "towerdefense.admin";
    private static final long FOUNDATION_STAGE = 1L;
    private static final Duration INVITATION_RETENTION =
            DefenseRepository.DEFAULT_INVITATION_RETENTION;
    private static final int MAX_TEAM_CHAT_LENGTH = 256;

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final CombatArea combatArea;
    private final DefenseRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final DefenseSessionManager sessions;
    private final CoreRegistry cores;
    private final ThirdPartyRegionProtectionAdapter regionProtection;
    private final RaidSealTagger sealTagger;
    private final Optional<TacticalBuildRepository> tacticalBuilds;
    private boolean startInFlight;
    private boolean startCancellationRequested;
    private boolean startRecoveryInFlight;
    private DefenseSession pendingRecoverySession;
    private Optional<UUID> pendingRecoverySealId = Optional.empty();
    private Optional<UUID> pendingRecoveryTacticalSessionId = Optional.empty();

    public TowerDefenseCommand(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores) {
        this(
                plugin,
                settings,
                repository,
                databaseExecutor,
                sessions,
                cores,
                ThirdPartyRegionProtectionAdapter.none(),
                new RaidSealTagger(plugin),
                Optional.empty());
    }

    public TowerDefenseCommand(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            ThirdPartyRegionProtectionAdapter regionProtection) {
        this(
                plugin,
                settings,
                repository,
                databaseExecutor,
                sessions,
                cores,
                regionProtection,
                new RaidSealTagger(plugin),
                Optional.empty());
    }

    public TowerDefenseCommand(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            ThirdPartyRegionProtectionAdapter regionProtection,
            RaidSealTagger sealTagger) {
        this(
                plugin,
                settings,
                repository,
                databaseExecutor,
                sessions,
                cores,
                regionProtection,
                sealTagger,
                Optional.empty());
    }

    public TowerDefenseCommand(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            ThirdPartyRegionProtectionAdapter regionProtection,
            RaidSealTagger sealTagger,
            TacticalBuildRepository tacticalBuilds) {
        this(
                plugin,
                settings,
                repository,
                databaseExecutor,
                sessions,
                cores,
                regionProtection,
                sealTagger,
                Optional.of(tacticalBuilds));
    }

    private TowerDefenseCommand(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            ThirdPartyRegionProtectionAdapter regionProtection,
            RaidSealTagger sealTagger,
            Optional<TacticalBuildRepository> tacticalBuilds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.regionProtection = Objects.requireNonNull(regionProtection, "regionProtection");
        this.sealTagger = Objects.requireNonNull(sealTagger, "sealTagger");
        this.tacticalBuilds = Objects.requireNonNull(tacticalBuilds, "tacticalBuilds");
        combatArea = new CombatArea(
                settings.combat().radius(),
                settings.combat().spawnInner(),
                settings.combat().spawnOuter(),
                settings.combat().minimumCoreDistance(),
                settings.combat().coreGap());
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("team")) {
            return teamCommand(sender, arguments);
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません。", NamedTextColor.RED));
            return true;
        }
        if (arguments.length < 2 || !arguments[0].equalsIgnoreCase("admin")) {
            sendUsage(sender);
            return true;
        }
        return switch (arguments[1].toLowerCase(java.util.Locale.ROOT)) {
            case "core" -> registerCore(sender);
            case "simulate" -> simulate(sender, arguments);
            case "status" -> showStatus(sender);
            case "abort" -> recover(sender);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean teamCommand(CommandSender sender, String[] arguments) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "チームコマンドはプレイヤーから実行してください。", NamedTextColor.RED));
            return true;
        }
        if (arguments.length < 2) {
            sendTeamUsage(player);
            return true;
        }
        return switch (arguments[1].toLowerCase(java.util.Locale.ROOT)) {
            case "invite" -> inviteTeamMember(player, arguments);
            case "invites" -> listTeamInvitations(player);
            case "accept" -> resolveTeamInvitation(player, arguments, true);
            case "decline" -> resolveTeamInvitation(player, arguments, false);
            case "rename" -> renameTeam(player, arguments);
            case "chat" -> teamChat(player, arguments);
            default -> {
                sendTeamUsage(player);
                yield true;
            }
        };
    }

    private boolean inviteTeamMember(Player player, String[] arguments) {
        if (arguments.length != 3) {
            sendTeamUsage(player);
            return true;
        }
        if (sessions.hasActiveSession()) {
            player.sendMessage(Component.text(
                    "防衛戦中はチームを変更できません。", NamedTextColor.RED));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(arguments[2]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(Component.text(
                    "そのプレイヤーはこのサーバーに参加した記録がありません。",
                    NamedTextColor.YELLOW));
            return true;
        }
        UUID actorId = player.getUniqueId();
        UUID inviteeId = target.getUniqueId();
        if (actorId.equals(inviteeId)) {
            player.sendMessage(Component.text(
                    "自分自身には招待を送れません。", NamedTextColor.YELLOW));
            return true;
        }
        Instant now = Instant.now();
        databaseExecutor.submit(() -> {
            TeamRecord team = repository.findTeamByMember(actorId).orElseThrow(
                    () -> new IllegalStateException("先にコアを設置してチームを作成してください"));
            UUID invitationId = UUID.randomUUID();
            return repository.createTeamInvitation(
                    team.id(),
                    actorId,
                    inviteeId,
                    invitationId,
                    UUID.randomUUID(),
                    now,
                    now.plus(INVITATION_RETENTION));
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(
                        "招待を作成できません: " + rootMessage(failure), NamedTextColor.RED));
                return;
            }
            TeamInvitation invitation = result.invitation();
            player.sendMessage(Component.text(
                    targetName(inviteeId) + "へチーム招待を送りました。招待コード: "
                            + shortInvitationId(invitation.id()),
                    NamedTextColor.GREEN));
            Player onlineTarget = Bukkit.getPlayer(inviteeId);
            if (onlineTarget != null) {
                onlineTarget.sendMessage(Component.text(
                        player.getName() + "からチーム「" + result.team().orElseThrow().displayName()
                                + "」への招待が届きました。/td team invites で確認してください。",
                        NamedTextColor.GREEN));
            }
        }));
        return true;
    }

    private boolean listTeamInvitations(Player player) {
        UUID playerId = player.getUniqueId();
        databaseExecutor.submit(() -> repository.findPendingTeamInvitations(playerId, Instant.now()).stream()
                .map(invitation -> new PendingInvitationView(
                        invitation,
                        repository.findTeam(invitation.teamId()).orElse(null)))
                .toList())
                .whenComplete((invitations, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        player.sendMessage(Component.text(
                                "招待を読み込めません: " + rootMessage(failure),
                                NamedTextColor.RED));
                        return;
                    }
                    if (invitations.isEmpty()) {
                        player.sendMessage(Component.text(
                                "保留中のチーム招待はありません。", NamedTextColor.GRAY));
                        return;
                    }
                    player.sendMessage(Component.text("保留中のチーム招待:", NamedTextColor.AQUA));
                    for (PendingInvitationView view : invitations) {
                        TeamInvitation invitation = view.invitation();
                        String teamName = view.team() == null
                                ? invitation.teamId().toString()
                                : view.team().displayName();
                        player.sendMessage(Component.text(
                                "- " + shortInvitationId(invitation.id()) + " : 「" + teamName
                                        + "」 from " + targetName(invitation.inviterId())
                                        + " /td team accept " + shortInvitationId(invitation.id())
                                        + "",
                                NamedTextColor.YELLOW));
                    }
                }));
        return true;
    }

    private boolean resolveTeamInvitation(
            Player player, String[] arguments, boolean accept) {
        if (arguments.length != 3) {
            sendTeamUsage(player);
            return true;
        }
        UUID playerId = player.getUniqueId();
        String token = arguments[2].toLowerCase(java.util.Locale.ROOT);
        databaseExecutor.submit(() -> {
            List<TeamInvitation> invitations = repository.findPendingTeamInvitations(
                    playerId, Instant.now());
            List<TeamInvitation> matches = invitations.stream()
                    .filter(invitation -> invitation.id().toString().startsWith(token))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalStateException(matches.isEmpty()
                        ? "招待コードが見つかりません。/td team invites で確認してください"
                        : "招待コードが複数一致します。より長いコードを指定してください");
            }
            TeamInvitation invitation = matches.get(0);
            return accept
                    ? repository.acceptTeamInvitation(
                            invitation.id(), playerId, UUID.randomUUID(), Instant.now())
                    : repository.declineTeamInvitation(
                            invitation.id(), playerId, UUID.randomUUID(), Instant.now());
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(
                        (accept ? "招待を承諾できません: " : "招待を辞退できません: ")
                                + rootMessage(failure),
                        NamedTextColor.RED));
                return;
            }
            String teamName = result.team()
                    .map(TeamRecord::displayName)
                    .orElse("チーム");
            player.sendMessage(Component.text(
                    accept ? "チーム「" + teamName + "」へ参加しました。"
                            : "チーム「" + teamName + "」への招待を辞退しました。",
                    NamedTextColor.GREEN));
            if (accept) {
                for (UUID memberId : result.team().orElseThrow().members()) {
                    Player member = Bukkit.getPlayer(memberId);
                    if (member != null && !member.getUniqueId().equals(playerId)) {
                        member.sendMessage(Component.text(
                                player.getName() + "がチームへ参加しました。", NamedTextColor.AQUA));
                    }
                }
            }
        }));
        return true;
    }

    private boolean renameTeam(Player player, String[] arguments) {
        if (arguments.length < 3) {
            sendTeamUsage(player);
            return true;
        }
        String displayName = String.join(" ", java.util.Arrays.copyOfRange(arguments, 2, arguments.length));
        UUID actorId = player.getUniqueId();
        databaseExecutor.submit(() -> {
            TeamRecord team = repository.findTeamByMember(actorId).orElseThrow(
                    () -> new IllegalStateException("所属チームがありません"));
            return repository.renameTeam(
                    team.id(), actorId, displayName, UUID.randomUUID(), Instant.now());
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(
                        "チーム名を変更できません: " + rootMessage(failure), NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(
                    "チーム名を「" + result.team().orElseThrow().displayName() + "」に変更しました。",
                    NamedTextColor.GREEN));
        }));
        return true;
    }

    private boolean teamChat(Player player, String[] arguments) {
        if (arguments.length < 3) {
            sendTeamUsage(player);
            return true;
        }
        String message = String.join(" ", java.util.Arrays.copyOfRange(arguments, 2, arguments.length));
        if (message.isBlank() || message.codePoints().count() > MAX_TEAM_CHAT_LENGTH
                || message.codePoints().anyMatch(Character::isISOControl)) {
            player.sendMessage(Component.text(
                    "チームチャットは空でない256文字以内の1行で指定してください。", NamedTextColor.YELLOW));
            return true;
        }
        UUID actorId = player.getUniqueId();
        databaseExecutor.submit(() -> repository.findTeamByMember(actorId).orElseThrow(
                () -> new IllegalStateException("所属チームがありません")))
                .whenComplete((team, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        player.sendMessage(Component.text(
                                "チームチャットを送信できません: " + rootMessage(failure),
                                NamedTextColor.RED));
                        return;
                    }
                    Component formatted = Component.text(
                            "[" + team.displayName() + "] " + player.getName() + ": " + message,
                            NamedTextColor.AQUA);
                    for (UUID memberId : team.members()) {
                        Player member = Bukkit.getPlayer(memberId);
                        if (member != null) {
                            member.sendMessage(formatted);
                        }
                    }
                }));
        return true;
    }

    private boolean registerCore(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("コア登録はプレイヤーから実行してください。", NamedTextColor.RED));
            return true;
        }
        if (sessions.hasActiveSession() || startInFlight) {
            sender.sendMessage(Component.text("防衛戦中はコアを登録できません。", NamedTextColor.RED));
            return true;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null || !target.getType().isSolid()) {
            sender.sendMessage(Component.text("6ブロック以内の固体ブロックを見てください。", NamedTextColor.RED));
            return true;
        }
        if (target.getWorld().getEnvironment() != World.Environment.NORMAL) {
            sender.sendMessage(Component.text("初版のコアはOverworldだけに登録できます。", NamedTextColor.RED));
            return true;
        }

        List<String> safetyViolations = PaperCombatAreaSafetyValidator.violations(
                target.getWorld(),
                target.getX() + 0.5d,
                target.getZ() + 0.5d,
                combatArea,
                settings.protection(),
                regionProtection);
        if (!safetyViolations.isEmpty()) {
            sender.sendMessage(Component.text(
                    "コア周辺が防衛戦の保護境界を満たしません: "
                            + String.join("; ", safetyViolations),
                    NamedTextColor.RED));
            return true;
        }

        UUID ownerId = player.getUniqueId();
        UUID teamId = soloTeamId(ownerId);
        UUID worldId = target.getWorld().getUID();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        long maximumHitPoints = settings.core().maxHealth();
        player.sendMessage(Component.text("コア位置を検証しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            TeamRecord team = repository.findTeamByOwner(ownerId)
                    .orElseGet(() -> repository.createSoloTeam(teamId, ownerId, Instant.now()));
            Instant now = Instant.now();
            CoreRecord core = new CoreRecord(
                    UUID.randomUUID(),
                    team.id(),
                    worldId,
                    x,
                    y,
                    z,
                    maximumHitPoints,
                    maximumHitPoints,
                    now,
                    now);
            return repository.placeCore(
                    ownerId,
                    core,
                    settings.combat().minimumCoreDistance(),
                    UUID.randomUUID(),
                    now);
        }).whenComplete((placement, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(
                        "コアを登録できません: " + rootMessage(failure),
                        NamedTextColor.RED));
                return;
            }
            CoreRecord core = placement.core().orElseThrow(
                    () -> new IllegalStateException("Core placement returned no core"));
            cores.replace(core);
            player.sendMessage(Component.text(
                    "このブロックをテスト用コアとして登録しました。",
                    NamedTextColor.GREEN));
        }));
        return true;
    }

    private boolean simulate(CommandSender sender, String[] arguments) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("シミュレーションはプレイヤーから開始してください。", NamedTextColor.RED));
            return true;
        }
        if (sessions.hasActiveSession() || startInFlight) {
            sender.sendMessage(Component.text("すでに防衛戦が進行中です。", NamedTextColor.RED));
            return true;
        }
        long stage = parseStage(arguments);
        WaveMutation waveMutation;
        try {
            waveMutation = parseWaveMutation(arguments);
            settings.waveMutations().snapshotFor(waveMutation);
        } catch (IllegalArgumentException invalidMutation) {
            sender.sendMessage(Component.text(
                    "指定したウェーブ変異は利用できません: " + invalidMutation.getMessage(),
                    NamedTextColor.RED));
            return true;
        }
        try {
            StageWaveSchedule.requireValidStageLevel(stage);
        } catch (IllegalArgumentException invalidStage) {
            sender.sendMessage(Component.text(
                    "指定したステージは利用できません: " + invalidStage.getMessage(),
                    NamedTextColor.RED));
            return true;
        }

        startInFlight = true;
        startCancellationRequested = false;
        UUID ownerId = player.getUniqueId();
        player.sendMessage(Component.text("永続データと開始ロックを確認しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            TeamRecord team = repository.findTeamByOwner(ownerId)
                    .orElseThrow(() -> new IllegalStateException("先に /td admin core を実行してください"));
            CoreRecord core = repository.findCoreByTeam(team.id())
                    .orElseThrow(() -> new IllegalStateException("先に /td admin core を実行してください"));
            return new StartData(team, core);
        }).whenComplete((startData, lookupFailure) -> runOnMainThread(() -> {
            if (lookupFailure != null) {
                completeStartOperation();
                player.sendMessage(Component.text(rootMessage(lookupFailure), NamedTextColor.RED));
                return;
            }
            if (startCancellationRequested) {
                completeStartOperation();
                player.sendMessage(Component.text("防衛戦の開始を取り消しました。", NamedTextColor.YELLOW));
                return;
            }
            beginStartTransaction(
                    player,
                    startData,
                    stage,
                    waveMutation,
                    Optional.empty(),
                    Optional.empty());
        }));
        return true;
    }

    /** Starts a player-facing event using one database-backed physical raid seal. */
    void startWithSeal(Player player, UUID coreId, long stage, UUID sealId) {
        startWithSeal(player, coreId, stage, sealId, WaveMutation.NONE, Optional.empty());
    }

    /** Starts a player-facing event with an explicit wave-mutation selection. */
    void startWithSeal(
            Player player,
            UUID coreId,
            long stage,
            UUID sealId,
            WaveMutation waveMutation) {
        startWithSeal(player, coreId, stage, sealId, waveMutation, Optional.empty());
    }

    /** Starts a player-facing event after binding a selected tactical build. */
    void startWithSeal(
            Player player,
            UUID coreId,
            long stage,
            UUID sealId,
            UUID tacticalSessionId) {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        startWithSeal(
                player,
                coreId,
                stage,
                sealId,
                WaveMutation.NONE,
                Optional.of(tacticalSessionId));
    }

    /** Starts a player-facing event with both a wave mutation and tactical build selection. */
    void startWithSeal(
            Player player,
            UUID coreId,
            long stage,
            UUID sealId,
            WaveMutation waveMutation,
            UUID tacticalSessionId) {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        startWithSeal(
                player,
                coreId,
                stage,
                sealId,
                waveMutation,
                Optional.of(tacticalSessionId));
    }

    private void startWithSeal(
            Player player,
            UUID coreId,
            long stage,
            UUID sealId,
            WaveMutation waveMutation,
            Optional<UUID> tacticalSessionId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(sealId, "sealId");
        Objects.requireNonNull(waveMutation, "waveMutation");
        if (sessions.hasActiveSession() || startInFlight) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            player.sendMessage(Component.text("すでに防衛戦が進行中です。", NamedTextColor.RED));
            return;
        }
        try {
            StageWaveSchedule.requireValidStageLevel(stage);
        } catch (IllegalArgumentException invalidStage) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            player.sendMessage(Component.text(
                    "指定したステージは利用できません: " + invalidStage.getMessage(),
                    NamedTextColor.RED));
            return;
        }
        if (!containsSealInInventory(player, sealId)) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            player.sendMessage(Component.text(
                    "開始に使う襲撃の印がインベントリにありません。", NamedTextColor.RED));
            return;
        }
        startInFlight = true;
        startCancellationRequested = false;
        player.sendMessage(Component.text("永続データと開始ロックを確認しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            TeamRecord team = repository.findTeamByMember(player.getUniqueId())
                    .orElseThrow(() -> new IllegalStateException(
                            "先にコアを設置したチームへ参加してください"));
            CoreRecord core = repository.findCore(coreId)
                    .orElseThrow(() -> new IllegalStateException("コアが見つかりません"));
            if (!core.teamId().equals(team.id())) {
                throw new IllegalStateException("このチームのコアではありません");
            }
            if (repository.loadTeamProgress(team.id()).unlockedLevel() < stage) {
                throw new IllegalStateException("このステージはまだ解放されていません");
            }
            return new StartData(team, core);
        }).whenComplete((startData, lookupFailure) -> runOnMainThread(() -> {
            if (lookupFailure != null) {
                cancelUnboundTacticalSession(player, tacticalSessionId);
                completeStartOperation();
                player.sendMessage(Component.text(rootMessage(lookupFailure), NamedTextColor.RED));
                return;
            }
            if (startCancellationRequested) {
                cancelUnboundTacticalSession(player, tacticalSessionId);
                completeStartOperation();
                player.sendMessage(Component.text("防衛戦の開始を取り消しました。", NamedTextColor.YELLOW));
                return;
            }
            beginStartTransaction(
                    player,
                    startData,
                    stage,
                    waveMutation,
                    Optional.of(sealId),
                    tacticalSessionId);
        }));
    }

    private void beginStartTransaction(
            Player player,
            StartData startData,
            long stage,
            WaveMutation waveMutation,
            Optional<UUID> sealId,
            Optional<UUID> tacticalSessionId) {
        if (sessions.hasActiveSession()) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            completeStartOperation();
            player.sendMessage(Component.text("すでに防衛戦が進行中です。", NamedTextColor.RED));
            return;
        }
        try {
            settings.waveMutations().snapshotFor(waveMutation);
        } catch (IllegalArgumentException invalidMutation) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            completeStartOperation();
            player.sendMessage(Component.text(
                    "ウェーブ変異を選択できません: " + invalidMutation.getMessage(),
                    NamedTextColor.RED));
            return;
        }
        if (sealId.isPresent() && !containsSealInInventory(player, sealId.orElseThrow())) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            completeStartOperation();
            player.sendMessage(Component.text(
                    "開始に使う襲撃の印がインベントリにありません。", NamedTextColor.RED));
            return;
        }
        CoreRecord core = startData.core();
        World world = Bukkit.getWorld(core.worldId());
        if (world == null) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            completeStartOperation();
            player.sendMessage(Component.text("コアのワールドが読み込まれていません。", NamedTextColor.RED));
            return;
        }
        List<String> safetyViolations = PaperCombatAreaSafetyValidator.violations(
                world,
                core.blockX() + 0.5d,
                core.blockZ() + 0.5d,
                combatArea,
                settings.protection(),
                regionProtection);
        if (!safetyViolations.isEmpty()) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            completeStartOperation();
            player.sendMessage(Component.text(
                    "防衛戦を開始できません。戦闘領域が保護境界に接触します: "
                            + String.join("; ", safetyViolations),
                    NamedTextColor.RED));
            return;
        }
        if (core.currentHitPoints() <= 0L) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            completeStartOperation();
            player.sendMessage(Component.text("コアは破壊済みです。", NamedTextColor.RED));
            return;
        }
        if (!player.getWorld().equals(world)
                || !combatArea.contains(
                        core.blockX() + 0.5d,
                        core.blockZ() + 0.5d,
                        player.getX(),
                        player.getZ())) {
            cancelUnboundTacticalSession(player, tacticalSessionId);
            completeStartOperation();
            player.sendMessage(Component.text("コアの戦闘範囲内に入ってください。", NamedTextColor.RED));
            return;
        }

        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                startData.team().id(),
                stage,
                settings.combat().maxParticipants(),
                new CoreState(
                        core.maximumHitPoints(),
                        core.currentHitPoints(),
                        true),
                settings.waveMutations().snapshotFor(waveMutation));
        Instant startedAt = Instant.now();
        StartRequest startRequest = new StartRequest(
                session.snapshot(),
                core.id(),
                settings.toString(),
                1,
                startedAt,
                sealId);
        databaseExecutor.submit(() -> sealId.isPresent()
                        ? repository.tryStartReserved(startRequest)
                        : repository.tryStart(startRequest))
                .whenComplete((outcome, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        cancelUnboundTacticalSession(player, tacticalSessionId);
                        completeStartOperation();
                        player.sendMessage(Component.text(
                                "防衛戦を開始できません: " + rootMessage(failure),
                                NamedTextColor.RED));
                        return;
                    }
                    if (outcome != StartOutcome.STARTED) {
                        cancelUnboundTacticalSession(player, tacticalSessionId);
                        completeStartOperation();
                        player.sendMessage(Component.text(
                                "別の防衛戦がデータベース上で進行中です。",
                                NamedTextColor.RED));
                        return;
                    }
                    if (tacticalSessionId.isPresent() && tacticalBuilds.isEmpty()) {
                        cancelUnboundTacticalSession(player, tacticalSessionId);
                        recoverUnactivatedSession(
                                player,
                                session,
                                sealId,
                                Optional.empty(),
                                "戦術ビルドの実行層を構成できないため防衛戦を復旧しました。");
                        return;
                    }
                    if (tacticalSessionId.isPresent()) {
                        UUID tacticalId = tacticalSessionId.orElseThrow();
                        databaseExecutor.submit(() -> tacticalBuilds.orElseThrow().bindToDefense(
                                        tacticalId,
                                        session.eventId(),
                                        UUID.randomUUID(),
                                        Instant.now()))
                                .whenComplete((bound, bindFailure) -> runOnMainThread(() -> {
                                    if (bindFailure != null
                                            || (bound != OperationOutcome.APPLIED
                                                    && bound != OperationOutcome.ALREADY_APPLIED)) {
                                        cancelUnboundTacticalSession(player, tacticalSessionId);
                                        player.sendMessage(Component.text(
                                                "選択した戦術ビルドを防衛戦へ結び付けられないため復旧します: "
                                                        + (bindFailure == null
                                                                ? String.valueOf(bound)
                                                                : rootMessage(bindFailure)),
                                                NamedTextColor.RED));
                                        recoverUnactivatedSession(
                                                player,
                                                session,
                                                sealId,
                                                Optional.empty(),
                                                "戦術ビルド未接続の防衛戦を技術的復旧しました。");
                                        return;
                                    }
                                    finishStartedSession(
                                            player,
                                            session,
                                            core,
                                            startData.team(),
                                            sealId,
                                            tacticalSessionId);
                                }));
                        return;
                    }
                    finishStartedSession(
                            player,
                            session,
                            core,
                            startData.team(),
                            sealId,
                            Optional.empty());
                }));
    }

    private void finishStartedSession(
            Player player,
            DefenseSession session,
            CoreRecord core,
            TeamRecord team,
            Optional<UUID> sealId,
            Optional<UUID> tacticalSessionId) {
        if (startCancellationRequested) {
            recoverUnactivatedSession(
                    player,
                    session,
                    sealId,
                    tacticalSessionId,
                    "防衛戦の開始を取り消しました。");
            return;
        }
        if (sealId.isPresent()) {
            UUID physicalSealId = sealId.orElseThrow();
            if (!removeMatchingSealItems(physicalSealId)) {
                recoverUnactivatedSession(
                        player,
                        session,
                        sealId,
                        tacticalSessionId,
                        "開始印を確認できないため防衛戦を取り消しました。");
                return;
            }
            databaseExecutor.submit(() -> repository.consumeReservedStartSeal(
                            session.eventId(), physicalSealId, Instant.now()))
                    .whenComplete((consumed, consumeFailure) -> runOnMainThread(() -> {
                        if (consumeFailure != null
                                || (consumed != OperationOutcome.APPLIED
                                        && consumed != OperationOutcome.ALREADY_APPLIED)) {
                            player.sendMessage(Component.text(
                                    "開始印の消費を確定できないため復旧します: "
                                            + (consumeFailure == null
                                                    ? String.valueOf(consumed)
                                                    : rootMessage(consumeFailure)),
                                    NamedTextColor.RED));
                            recoverUnactivatedSession(
                                    player,
                                    session,
                                    sealId,
                                    tacticalSessionId,
                                    "開始印の予約を技術的復旧しました。");
                            return;
                        }
                        activateSession(
                                player,
                                session,
                                core,
                                team,
                                sealId,
                                tacticalSessionId);
                    }));
            return;
        }
        activateSession(
                player,
                session,
                core,
                team,
                Optional.empty(),
                tacticalSessionId);
    }

    private void activateSession(
            Player player,
            DefenseSession session,
            CoreRecord core,
            TeamRecord team,
            Optional<UUID> sealId,
            Optional<UUID> tacticalSessionId) {
        if (startCancellationRequested) {
            recoverUnactivatedSession(
                    player,
                    session,
                    sealId,
                    tacticalSessionId,
                    "防衛戦の開始を取り消しました。");
            return;
        }
        try {
            sessions.activate(session, core, team.members());
            completeStartOperation();
        } catch (RuntimeException activationFailure) {
            player.sendMessage(Component.text(
                    "Paper実行層を開始できないため復旧します: "
                            + activationFailure.getMessage(),
                    NamedTextColor.RED));
            recoverUnactivatedSession(
                    player,
                    session,
                    sealId,
                    tacticalSessionId,
                    "Paper実行層の開始失敗を復旧しました。");
        }
    }

    private void recoverUnactivatedSession(
            CommandSender requester,
            DefenseSession session,
            Optional<UUID> sealId,
            Optional<UUID> tacticalSessionId,
            String successMessage) {
        if (startRecoveryInFlight) {
            return;
        }
        pendingRecoverySession = session;
        pendingRecoverySealId = sealId;
        pendingRecoveryTacticalSessionId = tacticalSessionId;
        startRecoveryInFlight = true;
        sealId.ifPresent(this::removeMatchingSealItems);
        databaseExecutor.execute(() -> {
                    tacticalSessionId.ifPresent(tacticalId -> tacticalBuilds.orElseThrow(
                            () -> new IllegalStateException(
                                    "tactical build repository is unavailable"))
                            .markTerminal(
                                    session.eventId(),
                                    TacticalTerminalResult.RECOVERY,
                                    UUID.randomUUID()));
                    repository.recoverUnfinishedEvent(
                            session.eventId(), UUID.randomUUID(), Instant.now());
                })
                .whenComplete((ignored, failure) -> runOnMainThread(() -> {
                    startRecoveryInFlight = false;
                    if (failure != null) {
                        requester.sendMessage(Component.text(
                                "開始済みイベントの復旧に失敗しました。/td admin abort で再試行できます: "
                                        + rootMessage(failure),
                                NamedTextColor.RED));
                        return;
                    }
                    completeStartOperation();
                    requester.sendMessage(Component.text(successMessage, NamedTextColor.YELLOW));
                }));
    }

    private void cancelUnboundTacticalSession(
            Player player,
            Optional<UUID> tacticalSessionId) {
        if (tacticalSessionId.isEmpty()) {
            return;
        }
        if (tacticalBuilds.isEmpty()) {
            plugin.getLogger().warning(
                    "Cannot cancel tactical session without its repository: "
                            + tacticalSessionId.orElseThrow());
            return;
        }
        databaseExecutor.execute(() -> tacticalBuilds.orElseThrow().cancelBeforeSelection(
                        tacticalSessionId.orElseThrow(),
                        player.getUniqueId(),
                        UUID.randomUUID(),
                        Instant.now()))
                .exceptionally(failure -> {
                    plugin.getLogger().warning(
                            "Could not cancel unbound tactical session "
                                    + tacticalSessionId.orElseThrow() + ": " + rootMessage(failure));
                    return null;
                });
    }

    private void completeStartOperation() {
        startInFlight = false;
        startCancellationRequested = false;
        startRecoveryInFlight = false;
        pendingRecoverySession = null;
        pendingRecoverySealId = Optional.empty();
        pendingRecoveryTacticalSessionId = Optional.empty();
    }

    private boolean containsSealInInventory(Player player, UUID sealId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (sealTagger.hasSealId(player.getInventory().getItem(slot), sealId)) {
                return true;
            }
        }
        return false;
    }

    private boolean removeMatchingSealItems(UUID sealId) {
        boolean removed = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                if (sealTagger.hasSealId(player.getInventory().getItem(slot), sealId)) {
                    player.getInventory().setItem(slot, null);
                    removed = true;
                }
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (sealTagger.hasSealId(item.getItemStack(), sealId)) {
                    item.remove();
                    removed = true;
                }
            }
        }
        return removed;
    }

    private boolean showStatus(CommandSender sender) {
        Optional<DefenseRuntimeStatus> status = sessions.status();
        if (status.isEmpty()) {
            if (startInFlight) {
                sender.sendMessage(Component.text(
                        "防衛戦の開始または開始後復旧を処理中です。",
                        NamedTextColor.YELLOW));
                return true;
            }
            sender.sendMessage(Component.text("実行中の防衛戦はありません。", NamedTextColor.GRAY));
            return true;
        }
        DefenseRuntimeStatus value = status.orElseThrow();
        sender.sendMessage(Component.text(
                "event=" + value.eventId()
                        + " phase=" + value.phase()
                        + " wave=" + value.currentWave() + "/" + value.totalWaves()
                        + " mutation=" + value.waveMutation().mutation().id()
                        + " enemies=" + value.aliveEnemies() + "+" + value.pendingEnemies()
                        + " core=" + value.coreHitPoints() + "/" + value.coreMaximumHitPoints()
                        + (value.ending() ? " ending" : "")
                        + " coreAttackers=" + value.coreAttackers()
                        + " coreAttacks=" + value.coreAttackCount()
                        + " pathInspections=" + value.pathMetrics().inspectionCount()
                        + " pathFailures=" + value.pathMetrics().inspectionFailureCount()
                        + " pathAvgNanos=" + value.pathMetrics().averageInspectionNanos()
                        + " pathMaxNanos=" + value.pathMetrics().maxInspectionNanos()
                        + " breakAttempts=" + value.pathMetrics().breakAttemptCount()
                        + " breakSuccesses=" + value.pathMetrics().breakSuccessCount()
                        + " bridgeAttempts=" + value.pathMetrics().bridgeAttemptCount()
                        + " bridgePlacements=" + value.pathMetrics().bridgePlacementCount()
                        + " terrainMutation="
                        + new TerrainMutationActivationGate(settings.terrainMutation()).status()
                        + (value.persistenceFailure() == null
                                ? ""
                                : " persistenceError=" + value.persistenceFailure()),
                value.persistenceFailure() == null
                        ? NamedTextColor.AQUA
                        : NamedTextColor.RED));
        return true;
    }

    private boolean recover(CommandSender sender) {
        if (sessions.recoverActiveSession()) {
            sender.sendMessage(Component.text("技術的復旧と清掃を開始しました。", NamedTextColor.YELLOW));
        } else if (startInFlight) {
            startCancellationRequested = true;
            if (pendingRecoverySession != null && !startRecoveryInFlight) {
                recoverUnactivatedSession(
                        sender,
                        pendingRecoverySession,
                        pendingRecoverySealId,
                        pendingRecoveryTacticalSessionId,
                        "開始済みイベントの技術的復旧を完了しました。");
            }
            sender.sendMessage(Component.text(
                    "開始処理を取り消し、DBロック取得済みなら直後に技術的復旧します。",
                    NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text("復旧対象の防衛戦はありません。", NamedTextColor.GRAY));
        }
        return true;
    }

    private static long parseStage(String[] arguments) {
        if (arguments.length < 3) {
            return FOUNDATION_STAGE;
        }
        try {
            return Long.parseLong(arguments[2]);
        } catch (NumberFormatException invalidStage) {
            return Long.MIN_VALUE;
        }
    }

    private static WaveMutation parseWaveMutation(String[] arguments) {
        if (arguments.length < 4) {
            return WaveMutation.NONE;
        }
        return WaveMutation.fromId(arguments[3]);
    }

    private static UUID soloTeamId(UUID ownerId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-tower-defense:solo:" + ownerId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void runOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        if (root instanceof CompletionException && root.getCause() != null) {
            root = root.getCause();
        }
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/td admin <core|simulate [stage] [swift|fortified|reinforcements]|status|abort>"
                        + " または /td team <invite|invites|accept|decline|rename|chat>",
                NamedTextColor.YELLOW));
    }

    private static void sendTeamUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/td team invite <player> | invites | accept <code> | decline <code>"
                        + " | rename <name> | chat <message>",
                NamedTextColor.YELLOW));
    }

    private static String shortInvitationId(UUID invitationId) {
        return invitationId.toString().substring(0, 8);
    }

    private static String targetName(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() == null ? playerId.toString().substring(0, 8) : player.getName();
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] arguments) {
        if (arguments.length == 1) {
            List<String> roots = sender.hasPermission(ADMIN_PERMISSION)
                    ? List.of("admin", "team")
                    : List.of("team");
            return matching(arguments[0], roots);
        }
        if (arguments.length >= 2 && arguments[0].equalsIgnoreCase("team")) {
            if (arguments.length == 2) {
                return matching(
                        arguments[1],
                        List.of("invite", "invites", "accept", "decline", "rename", "chat"));
            }
            return List.of();
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("admin")) {
            return matching(arguments[1], List.of("core", "simulate", "status", "abort"));
        }
        if (arguments.length == 3 && arguments[1].equalsIgnoreCase("simulate")) {
            return matching(
                    arguments[2],
                    RaidSealCatalog.recipeStages().stream().map(String::valueOf).toList());
        }
        if (arguments.length == 4 && arguments[1].equalsIgnoreCase("simulate")) {
            return matching(
                    arguments[3],
                    List.of("swift", "fortified", "reinforcements"));
        }
        return List.of();
    }

    private static List<String> matching(String prefix, List<String> candidates) {
        String normalized = prefix.toLowerCase(java.util.Locale.ROOT);
        return candidates.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private record StartData(TeamRecord team, CoreRecord core) {
        private StartData {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(core, "core");
        }
    }

    private record PendingInvitationView(TeamInvitation invitation, TeamRecord team) {
    }
}
