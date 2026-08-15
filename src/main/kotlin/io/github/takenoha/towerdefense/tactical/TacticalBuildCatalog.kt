package io.github.takenoha.towerdefense.tactical

import io.github.takenoha.towerdefense.domain.TowerType
import java.util.Objects
import java.util.Optional

/** Deterministic initial catalogue for the MVP tactical-build system. */
class TacticalBuildCatalog(definitions: List<TacticalBuildDefinition>?) {
    companion object {
        @JvmField
        val DEFINITION_VERSION: Int = 1

        @JvmField
        val GENERATOR_VERSION: Int = 1

        @JvmStatic
        fun defaults(): TacticalBuildCatalog = TacticalBuildCatalog(
            listOf(
                rapidFire(),
                longRange(),
                heavyFortress(),
                flameSuppression(),
                iceLightning(),
                finalDefenseLine(),
                arrowSpecialization(),
            ),
        )

        @JvmStatic
        private fun rapidFire(): TacticalBuildDefinition = build(
            "rapid-fire",
            "高速射撃陣",
            "射撃塔の攻撃間隔と低HP対象への決定力を高める。",
            TacticalBuildCategory.OFFENSE,
            TacticalBuildRarity.COMMON,
            "ARROW",
            setOf(TowerType.ARROW, TowerType.SNIPER),
            node(
                "rapid-fire",
                1,
                "ARROWの攻撃間隔 ×0.92",
                effect(
                    TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                    setOf(TowerType.ARROW),
                    0.92,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "rapid-fire",
                2,
                "SNIPERの攻撃間隔 ×0.92",
                effect(
                    TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                    setOf(TowerType.SNIPER),
                    0.92,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "rapid-fire",
                3,
                "ARROWのダメージ ×1.10",
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.ARROW),
                    1.10,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "rapid-fire",
                4,
                "低HP対象への射撃系ダメージ ×1.15",
                effect(
                    TacticalEffectType.DAMAGE_TO_LOW_HP_MULTIPLIER,
                    setOf(TowerType.ARROW, TowerType.SNIPER),
                    1.15,
                    TacticalTargetCondition.LOW_HP,
                ),
            ),
            node(
                "rapid-fire",
                5,
                "ARROWとSNIPERの攻撃間隔 ×0.92",
                effect(
                    TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                    setOf(TowerType.ARROW, TowerType.SNIPER),
                    0.92,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "rapid-fire",
                6,
                "ARROWとSNIPERのダメージ ×1.15",
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.ARROW, TowerType.SNIPER),
                    1.15,
                    TacticalTargetCondition.NONE,
                ),
            ),
        )

        @JvmStatic
        private fun longRange(): TacticalBuildDefinition = build(
            "long-range",
            "長距離精密陣",
            "射程と高耐久・ボスへの射撃を強化する。",
            TacticalBuildCategory.RANGE,
            TacticalBuildRarity.RARE,
            "SPYGLASS",
            setOf(TowerType.ARROW, TowerType.SNIPER),
            node(
                "long-range",
                1,
                "射程 +1.0",
                effect(
                    TacticalEffectType.RANGE_ADD,
                    setOf(TowerType.ARROW, TowerType.SNIPER),
                    1.0,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "long-range",
                2,
                "SNIPERのダメージ ×1.12",
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.SNIPER),
                    1.12,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "long-range",
                3,
                "高HP対象へのダメージ ×1.15",
                effect(
                    TacticalEffectType.DAMAGE_TO_HIGH_HP_MULTIPLIER,
                    setOf(TowerType.ARROW, TowerType.SNIPER),
                    1.15,
                    TacticalTargetCondition.HIGH_HP,
                ),
            ),
            node(
                "long-range",
                4,
                "射程 +1.5",
                effect(
                    TacticalEffectType.RANGE_ADD,
                    setOf(TowerType.ARROW, TowerType.SNIPER),
                    1.5,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "long-range",
                5,
                "ボスへのダメージ ×1.15",
                effect(
                    TacticalEffectType.DAMAGE_TO_BOSS_MULTIPLIER,
                    setOf(TowerType.ARROW, TowerType.SNIPER),
                    1.15,
                    TacticalTargetCondition.BOSS,
                ),
            ),
            node(
                "long-range",
                6,
                "ARROWとSNIPERのダメージ ×1.18",
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.ARROW, TowerType.SNIPER),
                    1.18,
                    TacticalTargetCondition.NONE,
                ),
            ),
        )

        @JvmStatic
        private fun heavyFortress(): TacticalBuildDefinition = build(
            "heavy-fortress",
            "重砲要塞",
            "範囲攻撃と支援を軸に防衛線を固める。",
            TacticalBuildCategory.SIEGE,
            TacticalBuildRarity.RARE,
            "TNT",
            setOf(TowerType.CANNON, TowerType.FLAME, TowerType.SUPPORT),
            node(
                "heavy-fortress",
                1,
                "CANNONの範囲半径 ×1.10",
                effect(
                    TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                    setOf(TowerType.CANNON),
                    1.10,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "heavy-fortress",
                2,
                "CANNONのダメージ ×1.12",
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.CANNON),
                    1.12,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "heavy-fortress",
                3,
                "CANNONの攻撃間隔 ×1.08、ダメージ ×1.18",
                effect(
                    TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                    setOf(TowerType.CANNON),
                    1.08,
                    TacticalTargetCondition.NONE,
                ),
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.CANNON),
                    1.18,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "heavy-fortress",
                4,
                "FLAMEのダメージ ×1.12",
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.FLAME),
                    1.12,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "heavy-fortress",
                5,
                "SUPPORTのバフ倍率 ×1.10",
                effect(
                    TacticalEffectType.SUPPORT_BUFF_MULTIPLIER,
                    setOf(TowerType.SUPPORT),
                    1.10,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "heavy-fortress",
                6,
                "CANNONとFLAMEの範囲半径 ×1.15",
                effect(
                    TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                    setOf(TowerType.CANNON, TowerType.FLAME),
                    1.15,
                    TacticalTargetCondition.NONE,
                ),
            ),
        )

        @JvmStatic
        private fun flameSuppression(): TacticalBuildDefinition = build(
            "flame-suppression",
            "火炎制圧",
            "燃焼中の敵を範囲攻撃で押し返す。ブロック着火は行わない。",
            TacticalBuildCategory.CONTROL,
            TacticalBuildRarity.RARE,
            "BLAZE_POWDER",
            setOf(TowerType.FLAME, TowerType.CANNON),
            node(
                "flame-suppression",
                1,
                "FLAMEの燃焼時間 ×1.20",
                effect(
                    TacticalEffectType.BURN_DURATION_MULTIPLIER,
                    setOf(TowerType.FLAME),
                    1.20,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "flame-suppression",
                2,
                "FLAMEのダメージ ×1.10",
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.FLAME),
                    1.10,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "flame-suppression",
                3,
                "燃焼中対象へのFLAMEダメージ ×1.15",
                effect(
                    TacticalEffectType.DAMAGE_TO_BURNING_TARGET_MULTIPLIER,
                    setOf(TowerType.FLAME),
                    1.15,
                    TacticalTargetCondition.BURNING,
                ),
            ),
            node(
                "flame-suppression",
                4,
                "燃焼中対象へのCANNONダメージ ×1.12",
                effect(
                    TacticalEffectType.DAMAGE_TO_BURNING_TARGET_MULTIPLIER,
                    setOf(TowerType.CANNON),
                    1.12,
                    TacticalTargetCondition.BURNING,
                ),
            ),
            node(
                "flame-suppression",
                5,
                "FLAMEの範囲半径 ×1.12",
                effect(
                    TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                    setOf(TowerType.FLAME),
                    1.12,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "flame-suppression",
                6,
                "燃焼中対象へのCANNON・FLAMEダメージ ×1.18",
                effect(
                    TacticalEffectType.DAMAGE_TO_BURNING_TARGET_MULTIPLIER,
                    setOf(TowerType.CANNON, TowerType.FLAME),
                    1.18,
                    TacticalTargetCondition.BURNING,
                ),
            ),
        )

        @JvmStatic
        private fun iceLightning(): TacticalBuildDefinition = build(
            "ice-lightning",
            "氷雷連鎖",
            "減速した敵へ連鎖攻撃を集中させる。",
            TacticalBuildCategory.CONTROL,
            TacticalBuildRarity.EPIC,
            "ICE",
            setOf(TowerType.FROST, TowerType.LIGHTNING),
            node(
                "ice-lightning",
                1,
                "FROSTの減速強度 ×1.10",
                effect(
                    TacticalEffectType.SLOW_STRENGTH_MULTIPLIER,
                    setOf(TowerType.FROST),
                    1.10,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "ice-lightning",
                2,
                "LIGHTNINGのチェイン数 +1",
                effect(
                    TacticalEffectType.CHAIN_COUNT_ADD,
                    setOf(TowerType.LIGHTNING),
                    1.0,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "ice-lightning",
                3,
                "減速中対象へのLIGHTNINGダメージ ×1.12",
                effect(
                    TacticalEffectType.DAMAGE_TO_SLOWED_TARGET_MULTIPLIER,
                    setOf(TowerType.LIGHTNING),
                    1.12,
                    TacticalTargetCondition.SLOWED,
                ),
            ),
            node(
                "ice-lightning",
                4,
                "FROSTの範囲半径 ×1.10",
                effect(
                    TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                    setOf(TowerType.FROST),
                    1.10,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "ice-lightning",
                5,
                "LIGHTNINGのダメージ ×1.12",
                effect(
                    TacticalEffectType.DAMAGE_MULTIPLIER,
                    setOf(TowerType.LIGHTNING),
                    1.12,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "ice-lightning",
                6,
                "LIGHTNINGのチェイン数 +1、減速中へのダメージ ×1.12",
                effect(
                    TacticalEffectType.CHAIN_COUNT_ADD,
                    setOf(TowerType.LIGHTNING),
                    1.0,
                    TacticalTargetCondition.NONE,
                ),
                effect(
                    TacticalEffectType.DAMAGE_TO_SLOWED_TARGET_MULTIPLIER,
                    setOf(TowerType.LIGHTNING),
                    1.12,
                    TacticalTargetCondition.SLOWED,
                ),
            ),
        )

        @JvmStatic
        private fun finalDefenseLine(): TacticalBuildDefinition = build(
            "final-defense-line",
            "最終防衛線",
            "支援、修理、コア危機時の全体強化を組み合わせる。",
            TacticalBuildCategory.DEFENSE,
            TacticalBuildRarity.EPIC,
            "BEACON",
            setOf(
                TowerType.SUPPORT,
                TowerType.ARROW,
                TowerType.CANNON,
                TowerType.FROST,
                TowerType.LIGHTNING,
                TowerType.SNIPER,
                TowerType.FLAME,
            ),
            node(
                "final-defense-line",
                1,
                "SUPPORTの効果範囲 +1.0",
                effect(
                    TacticalEffectType.RANGE_ADD,
                    setOf(TowerType.SUPPORT),
                    1.0,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "final-defense-line",
                2,
                "タワー修理費 ×0.90",
                effect(
                    TacticalEffectType.REPAIR_COST_MULTIPLIER,
                    emptySet(),
                    0.90,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "final-defense-line",
                3,
                "SUPPORTのバフ倍率 ×1.10",
                effect(
                    TacticalEffectType.SUPPORT_BUFF_MULTIPLIER,
                    setOf(TowerType.SUPPORT),
                    1.10,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "final-defense-line",
                4,
                "コアHP50%未満で全タワーダメージ ×1.10",
                effect(
                    TacticalEffectType.CORE_LOW_HP_DAMAGE_MULTIPLIER,
                    emptySet(),
                    1.10,
                    TacticalTargetCondition.CORE_BELOW_50_PERCENT,
                ),
            ),
            node(
                "final-defense-line",
                5,
                "全タワーの被ダメージ ×0.90",
                effect(
                    TacticalEffectType.TOWER_DAMAGE_TAKEN_MULTIPLIER,
                    emptySet(),
                    0.90,
                    TacticalTargetCondition.NONE,
                ),
            ),
            node(
                "final-defense-line",
                6,
                "コアHP30%未満で全タワー攻撃間隔 ×0.85",
                effect(
                    TacticalEffectType.CORE_LOW_HP_ATTACK_INTERVAL_MULTIPLIER,
                    emptySet(),
                    0.85,
                    TacticalTargetCondition.CORE_BELOW_30_PERCENT,
                ),
            ),
        )

        @JvmStatic
        private fun arrowSpecialization(): TacticalBuildDefinition {
            val buildId = "arrow-specialization"
            val branchGroup = "arrow-path"
            return build(
                buildId,
                "ARROW特化分岐",
                "ARROWを連射型または射程型へ特化させる二択の分岐ツリー。",
                TacticalBuildCategory.OFFENSE,
                TacticalBuildRarity.RARE,
                "ARROW",
                setOf(TowerType.ARROW),
                branchNode(
                    buildId,
                    "rapid-fire",
                    branchGroup,
                    1,
                    "連射 Tier 1: ARROWの攻撃間隔 ×0.92",
                    null,
                    effect(
                        TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                        setOf(TowerType.ARROW),
                        0.92,
                        TacticalTargetCondition.NONE,
                    ),
                ),
                branchNode(
                    buildId,
                    "rapid-fire",
                    branchGroup,
                    2,
                    "連射 Tier 2: ARROWのダメージ ×1.10",
                    "$buildId-rapid-fire-tier-1",
                    effect(
                        TacticalEffectType.DAMAGE_MULTIPLIER,
                        setOf(TowerType.ARROW),
                        1.10,
                        TacticalTargetCondition.NONE,
                    ),
                ),
                branchNode(
                    buildId,
                    "rapid-fire",
                    branchGroup,
                    3,
                    "連射 Tier 3: 低HP対象へのARROWダメージ ×1.15",
                    "$buildId-rapid-fire-tier-2",
                    effect(
                        TacticalEffectType.DAMAGE_TO_LOW_HP_MULTIPLIER,
                        setOf(TowerType.ARROW),
                        1.15,
                        TacticalTargetCondition.LOW_HP,
                    ),
                ),
                branchNode(
                    buildId,
                    "range",
                    branchGroup,
                    1,
                    "射程 Tier 1: ARROWの射程 +1.0",
                    null,
                    effect(
                        TacticalEffectType.RANGE_ADD,
                        setOf(TowerType.ARROW),
                        1.0,
                        TacticalTargetCondition.NONE,
                    ),
                ),
                branchNode(
                    buildId,
                    "range",
                    branchGroup,
                    2,
                    "射程 Tier 2: ARROWの射程 +1.5",
                    "$buildId-range-tier-1",
                    effect(
                        TacticalEffectType.RANGE_ADD,
                        setOf(TowerType.ARROW),
                        1.5,
                        TacticalTargetCondition.NONE,
                    ),
                ),
                branchNode(
                    buildId,
                    "range",
                    branchGroup,
                    3,
                    "射程 Tier 3: 高HP対象へのARROWダメージ ×1.15",
                    "$buildId-range-tier-2",
                    effect(
                        TacticalEffectType.DAMAGE_TO_HIGH_HP_MULTIPLIER,
                        setOf(TowerType.ARROW),
                        1.15,
                        TacticalTargetCondition.HIGH_HP,
                    ),
                ),
            )
        }

        @JvmStatic
        private fun build(
            id: String,
            displayName: String,
            description: String,
            category: TacticalBuildCategory,
            rarity: TacticalBuildRarity,
            iconMaterial: String,
            targets: Set<TowerType>,
            vararg nodes: TacticalSkillNodeDefinition,
        ): TacticalBuildDefinition = TacticalBuildDefinition(
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
            Optional.empty(),
            nodes.toList(),
        )

        @JvmStatic
        private fun node(
            buildId: String,
            tier: Int,
            description: String,
            vararg effects: TacticalEffectEntry,
        ): TacticalSkillNodeDefinition = TacticalSkillNodeDefinition(
            "$buildId-tier-$tier",
            DEFINITION_VERSION,
            tier,
            "Tier $tier",
            description,
            effects.toList(),
        )

        @JvmStatic
        private fun branchNode(
            buildId: String,
            branchId: String,
            exclusiveBranchGroup: String,
            tier: Int,
            description: String,
            prerequisiteNodeId: String?,
            vararg effects: TacticalEffectEntry,
        ): TacticalSkillNodeDefinition = TacticalSkillNodeDefinition(
            "$buildId-$branchId-tier-$tier",
            DEFINITION_VERSION,
            tier,
            (if (branchId == "rapid-fire") "連射" else "射程") + " Tier " + tier,
            description,
            effects.toList(),
            if (prerequisiteNodeId == null) emptyList() else listOf(prerequisiteNodeId),
            Optional.of(exclusiveBranchGroup),
            Optional.of(branchId),
        )

        @JvmStatic
        private fun effect(
            type: TacticalEffectType,
            towerTypes: Set<TowerType>,
            value: Double,
            condition: TacticalTargetCondition,
        ): TacticalEffectEntry = TacticalEffectEntry(
            type,
            towerTypes,
            value,
            condition,
            null,
            null,
        )
    }

    private val definitions: List<TacticalBuildDefinition>
    private val byId: Map<String, TacticalBuildDefinition>

    init {
        val checkedDefinitions = Objects.requireNonNull(definitions, "definitions")
        TacticalBuildDefinitionValidator.validateAll(checkedDefinitions)
        this.definitions = java.util.List.copyOf(checkedDefinitions)
        this.byId = java.util.Map.copyOf(this.definitions.associateBy { it.id() })
    }

    fun definitions(): List<TacticalBuildDefinition> = definitions

    fun enabledDefinitions(): List<TacticalBuildDefinition> =
        java.util.List.copyOf(definitions.filter { it.enabled() })

    fun require(id: String?): TacticalBuildDefinition =
        Objects.requireNonNull(byId[id], "Unknown tactical build: $id")!!
}
