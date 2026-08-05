package io.github.takenoha.towerdefense.persistence;

/** Temporary stat that can be purchased with an event's team-shared battle funds. */
public enum BattleBoostKind {
    POWER("power"),
    SPEED("speed"),
    RANGE("range");

    private final String id;

    BattleBoostKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
