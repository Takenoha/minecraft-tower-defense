package io.github.takenoha.towerdefense.tactical;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Deterministic initial catalogue for the MVP tactical-build system. */
public final class TacticalBuildCatalog {
    public static final int DEFINITION_VERSION = 1;
    public static final int GENERATOR_VERSION = 1;

    private final List<TacticalBuildDefinition> definitions;
    private final Map<String, TacticalBuildDefinition> byId;

    public TacticalBuildCatalog(List<TacticalBuildDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        TacticalBuildDefinitionValidator.validateAll(definitions);
        this.definitions = List.copyOf(definitions);
        byId = this.definitions.stream().collect(Collectors.toUnmodifiableMap(
                TacticalBuildDefinition::id,
                Function.identity()));
    }

    public static TacticalBuildCatalog defaults() {
        return new TacticalBuildCatalog(List.of(
                rapidFire(),
                longRange(),
                heavyFortress(),
                flameSuppression(),
                iceLightning(),
                finalDefenseLine()));
    }

    public List<TacticalBuildDefinition> definitions() {
        return definitions;
    }

    public List<TacticalBuildDefinition> enabledDefinitions() {
        return definitions.stream().filter(TacticalBuildDefinition::enabled).toList();
    }

    public TacticalBuildDefinition require(String id) {
        return Objects.requireNonNull(byId.get(id), "Unknown tactical build: " + id);
    }

    private static TacticalBuildDefinition rapidFire() {
        return build(
                "rapid-fire",
                "高速射撃陣",
                "射撃塔の攻撃間隔と低HP対象への決定力を高める。",
                TacticalBuildCategory.OFFENSE,
                TacticalBuildRarity.COMMON,
                "ARROW",
                Set.of(TowerType.ARROW, TowerType.SNIPER),
                node("rapid-fire", 1, "ARROWの攻撃間隔 ×0.92", effect(
                        TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                        Set.of(TowerType.ARROW), 0.92d, TacticalTargetCondition.NONE)),
                node("rapid-fire", 2, "SNIPERの攻撃間隔 ×0.92", effect(
                        TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                        Set.of(TowerType.SNIPER), 0.92d, TacticalTargetCondition.NONE)),
                node("rapid-fire", 3, "ARROWのダメージ ×1.10", effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.ARROW), 1.10d, TacticalTargetCondition.NONE)),
                node("rapid-fire", 4, "低HP対象への射撃系ダメージ ×1.15", effect(
                        TacticalEffectType.DAMAGE_TO_LOW_HP_MULTIPLIER,
                        Set.of(TowerType.ARROW, TowerType.SNIPER), 1.15d,
                        TacticalTargetCondition.LOW_HP)),
                node("rapid-fire", 5, "ARROWとSNIPERの攻撃間隔 ×0.92", effect(
                        TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                        Set.of(TowerType.ARROW, TowerType.SNIPER), 0.92d,
                        TacticalTargetCondition.NONE)),
                node("rapid-fire", 6, "ARROWとSNIPERのダメージ ×1.15", effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.ARROW, TowerType.SNIPER), 1.15d,
                        TacticalTargetCondition.NONE)));
    }

    private static TacticalBuildDefinition longRange() {
        return build(
                "long-range",
                "長距離精密陣",
                "射程と高耐久・ボスへの射撃を強化する。",
                TacticalBuildCategory.RANGE,
                TacticalBuildRarity.RARE,
                "SPYGLASS",
                Set.of(TowerType.ARROW, TowerType.SNIPER),
                node("long-range", 1, "射程 +1.0", effect(
                        TacticalEffectType.RANGE_ADD,
                        Set.of(TowerType.ARROW, TowerType.SNIPER), 1.0d,
                        TacticalTargetCondition.NONE)),
                node("long-range", 2, "SNIPERのダメージ ×1.12", effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.SNIPER), 1.12d, TacticalTargetCondition.NONE)),
                node("long-range", 3, "高HP対象へのダメージ ×1.15", effect(
                        TacticalEffectType.DAMAGE_TO_HIGH_HP_MULTIPLIER,
                        Set.of(TowerType.ARROW, TowerType.SNIPER), 1.15d,
                        TacticalTargetCondition.HIGH_HP)),
                node("long-range", 4, "射程 +1.5", effect(
                        TacticalEffectType.RANGE_ADD,
                        Set.of(TowerType.ARROW, TowerType.SNIPER), 1.5d,
                        TacticalTargetCondition.NONE)),
                node("long-range", 5, "ボスへのダメージ ×1.15", effect(
                        TacticalEffectType.DAMAGE_TO_BOSS_MULTIPLIER,
                        Set.of(TowerType.ARROW, TowerType.SNIPER), 1.15d,
                        TacticalTargetCondition.BOSS)),
                node("long-range", 6, "ARROWとSNIPERのダメージ ×1.18", effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.ARROW, TowerType.SNIPER), 1.18d,
                        TacticalTargetCondition.NONE)));
    }

    private static TacticalBuildDefinition heavyFortress() {
        return build(
                "heavy-fortress",
                "重砲要塞",
                "範囲攻撃と支援を軸に防衛線を固める。",
                TacticalBuildCategory.SIEGE,
                TacticalBuildRarity.RARE,
                "TNT",
                Set.of(TowerType.CANNON, TowerType.FLAME, TowerType.SUPPORT),
                node("heavy-fortress", 1, "CANNONの範囲半径 ×1.10", effect(
                        TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                        Set.of(TowerType.CANNON), 1.10d, TacticalTargetCondition.NONE)),
                node("heavy-fortress", 2, "CANNONのダメージ ×1.12", effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.CANNON), 1.12d, TacticalTargetCondition.NONE)),
                node("heavy-fortress", 3, "CANNONの攻撃間隔 ×1.08、ダメージ ×1.18", effect(
                        TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                        Set.of(TowerType.CANNON), 1.08d, TacticalTargetCondition.NONE), effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.CANNON), 1.18d, TacticalTargetCondition.NONE)),
                node("heavy-fortress", 4, "FLAMEのダメージ ×1.12", effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.FLAME), 1.12d, TacticalTargetCondition.NONE)),
                node("heavy-fortress", 5, "SUPPORTのバフ倍率 ×1.10", effect(
                        TacticalEffectType.SUPPORT_BUFF_MULTIPLIER,
                        Set.of(TowerType.SUPPORT), 1.10d, TacticalTargetCondition.NONE)),
                node("heavy-fortress", 6, "CANNONとFLAMEの範囲半径 ×1.15", effect(
                        TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                        Set.of(TowerType.CANNON, TowerType.FLAME), 1.15d,
                        TacticalTargetCondition.NONE)));
    }

    private static TacticalBuildDefinition flameSuppression() {
        return build(
                "flame-suppression",
                "火炎制圧",
                "燃焼中の敵を範囲攻撃で押し返す。ブロック着火は行わない。",
                TacticalBuildCategory.CONTROL,
                TacticalBuildRarity.RARE,
                "BLAZE_POWDER",
                Set.of(TowerType.FLAME, TowerType.CANNON),
                node("flame-suppression", 1, "FLAMEの燃焼時間 ×1.20", effect(
                        TacticalEffectType.BURN_DURATION_MULTIPLIER,
                        Set.of(TowerType.FLAME), 1.20d, TacticalTargetCondition.NONE)),
                node("flame-suppression", 2, "FLAMEのダメージ ×1.10", effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.FLAME), 1.10d, TacticalTargetCondition.NONE)),
                node("flame-suppression", 3, "燃焼中対象へのFLAMEダメージ ×1.15", effect(
                        TacticalEffectType.DAMAGE_TO_BURNING_TARGET_MULTIPLIER,
                        Set.of(TowerType.FLAME), 1.15d, TacticalTargetCondition.BURNING)),
                node("flame-suppression", 4, "燃焼中対象へのCANNONダメージ ×1.12", effect(
                        TacticalEffectType.DAMAGE_TO_BURNING_TARGET_MULTIPLIER,
                        Set.of(TowerType.CANNON), 1.12d, TacticalTargetCondition.BURNING)),
                node("flame-suppression", 5, "FLAMEの範囲半径 ×1.12", effect(
                        TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                        Set.of(TowerType.FLAME), 1.12d, TacticalTargetCondition.NONE)),
                node("flame-suppression", 6, "燃焼中対象へのCANNON・FLAMEダメージ ×1.18", effect(
                        TacticalEffectType.DAMAGE_TO_BURNING_TARGET_MULTIPLIER,
                        Set.of(TowerType.CANNON, TowerType.FLAME), 1.18d,
                        TacticalTargetCondition.BURNING)));
    }

    private static TacticalBuildDefinition iceLightning() {
        return build(
                "ice-lightning",
                "氷雷連鎖",
                "減速した敵へ連鎖攻撃を集中させる。",
                TacticalBuildCategory.CONTROL,
                TacticalBuildRarity.EPIC,
                "ICE",
                Set.of(TowerType.FROST, TowerType.LIGHTNING),
                node("ice-lightning", 1, "FROSTの減速強度 ×1.10", effect(
                        TacticalEffectType.SLOW_STRENGTH_MULTIPLIER,
                        Set.of(TowerType.FROST), 1.10d, TacticalTargetCondition.NONE)),
                node("ice-lightning", 2, "LIGHTNINGのチェイン数 +1", effect(
                        TacticalEffectType.CHAIN_COUNT_ADD,
                        Set.of(TowerType.LIGHTNING), 1.0d, TacticalTargetCondition.NONE)),
                node("ice-lightning", 3, "減速中対象へのLIGHTNINGダメージ ×1.12", effect(
                        TacticalEffectType.DAMAGE_TO_SLOWED_TARGET_MULTIPLIER,
                        Set.of(TowerType.LIGHTNING), 1.12d, TacticalTargetCondition.SLOWED)),
                node("ice-lightning", 4, "FROSTの範囲半径 ×1.10", effect(
                        TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                        Set.of(TowerType.FROST), 1.10d, TacticalTargetCondition.NONE)),
                node("ice-lightning", 5, "LIGHTNINGのダメージ ×1.12", effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        Set.of(TowerType.LIGHTNING), 1.12d, TacticalTargetCondition.NONE)),
                node("ice-lightning", 6, "LIGHTNINGのチェイン数 +1、減速中へのダメージ ×1.12", effect(
                        TacticalEffectType.CHAIN_COUNT_ADD,
                        Set.of(TowerType.LIGHTNING), 1.0d, TacticalTargetCondition.NONE), effect(
                        TacticalEffectType.DAMAGE_TO_SLOWED_TARGET_MULTIPLIER,
                        Set.of(TowerType.LIGHTNING), 1.12d, TacticalTargetCondition.SLOWED)));
    }

    private static TacticalBuildDefinition finalDefenseLine() {
        return build(
                "final-defense-line",
                "最終防衛線",
                "支援、修理、コア危機時の全体強化を組み合わせる。",
                TacticalBuildCategory.DEFENSE,
                TacticalBuildRarity.EPIC,
                "BEACON",
                Set.of(TowerType.SUPPORT, TowerType.ARROW, TowerType.CANNON,
                        TowerType.FROST, TowerType.LIGHTNING, TowerType.SNIPER,
                        TowerType.FLAME),
                node("final-defense-line", 1, "SUPPORTの効果範囲 +1.0", effect(
                        TacticalEffectType.RANGE_ADD,
                        Set.of(TowerType.SUPPORT), 1.0d, TacticalTargetCondition.NONE)),
                node("final-defense-line", 2, "タワー修理費 ×0.90", effect(
                        TacticalEffectType.REPAIR_COST_MULTIPLIER,
                        Set.of(), 0.90d, TacticalTargetCondition.NONE)),
                node("final-defense-line", 3, "SUPPORTのバフ倍率 ×1.10", effect(
                        TacticalEffectType.SUPPORT_BUFF_MULTIPLIER,
                        Set.of(TowerType.SUPPORT), 1.10d, TacticalTargetCondition.NONE)),
                node("final-defense-line", 4, "コアHP50%未満で全タワーダメージ ×1.10", effect(
                        TacticalEffectType.CORE_LOW_HP_DAMAGE_MULTIPLIER,
                        Set.of(), 1.10d, TacticalTargetCondition.CORE_BELOW_50_PERCENT)),
                node("final-defense-line", 5, "全タワーの被ダメージ ×0.90", effect(
                        TacticalEffectType.TOWER_DAMAGE_TAKEN_MULTIPLIER,
                        Set.of(), 0.90d, TacticalTargetCondition.NONE)),
                node("final-defense-line", 6, "コアHP30%未満で全タワー攻撃間隔 ×0.85", effect(
                        TacticalEffectType.CORE_LOW_HP_ATTACK_INTERVAL_MULTIPLIER,
                        Set.of(), 0.85d, TacticalTargetCondition.CORE_BELOW_30_PERCENT)));
    }

    private static TacticalBuildDefinition build(
            String id,
            String displayName,
            String description,
            TacticalBuildCategory category,
            TacticalBuildRarity rarity,
            String iconMaterial,
            Set<TowerType> targets,
            TacticalSkillNodeDefinition... nodes) {
        List<TacticalSkillNodeDefinition> nodeList = new ArrayList<>(List.of(nodes));
        return new TacticalBuildDefinition(
                id,
                DEFINITION_VERSION,
                displayName,
                description,
                category,
                rarity,
                true,
                1,
                iconMaterial,
                targets,
                java.util.Optional.empty(),
                nodeList);
    }

    private static TacticalSkillNodeDefinition node(
            String buildId,
            int tier,
            String description,
            TacticalEffectEntry... effects) {
        return new TacticalSkillNodeDefinition(
                buildId + "-tier-" + tier,
                DEFINITION_VERSION,
                tier,
                "Tier " + tier,
                description,
                List.of(effects));
    }

    private static TacticalEffectEntry effect(
            TacticalEffectType type,
            Set<TowerType> towerTypes,
            double value,
            TacticalTargetCondition condition) {
        return new TacticalEffectEntry(type, towerTypes, value, condition, null, null);
    }
}
