package com.k33bz.sanctuary.event;

import net.minecraft.ChatFormatting;

import java.util.Locale;

/**
 * The set of themed nightly events. The DECLARATION ORDER IS CANONICAL AND MUST NEVER CHANGE — the
 * deterministic schedule ({@link EventSchedule}) and the mc.kast.ro website both index events by this
 * order, so reordering (or renaming a key) silently reshuffles the entire published schedule. Add new
 * events only at the END.
 */
public enum NightEvent {
    /** Nothing special — the weighted majority, so most nights stay plain. */
    ORDINARY("Ordinary Night", ChatFormatting.GRAY, "#7f8c8d"),
    /** Buffed + more numerous WILD mob spawns; sanctuaries stay safe. */
    BLOOD_MOON("Blood Moon", ChatFormatting.DARK_RED, "#c0392b"),
    /** Wild hostiles actively seek players and break player-placed doors. */
    THE_HUNT("The Hunt", ChatFormatting.GOLD, "#d35400"),
    /** Dodgeable falling hazards + distance-tiered drops out in the wild. */
    METEOR_SHOWER("Meteor Shower", ChatFormatting.DARK_PURPLE, "#8e44ad"),
    /** A calm night — fewer spawns and a small boon. */
    STILL_NIGHT("Still Night", ChatFormatting.BLUE, "#2980b9"),
    /** UNDERGROUND: foul air suffocates deep wild players unless they carry Water Breathing. */
    BAD_AIR("Bad Air", ChatFormatting.DARK_GREEN, "#6b8e23"),
    /** UNDERGROUND: caves seethe with extra hostiles around deep wild players. */
    THE_SWARM("The Swarm", ChatFormatting.DARK_AQUA, "#148f77"),
    /** UNDERGROUND: the deep rumbles — disorienting cave-ins for players below the surface. */
    TREMORS("Tremors", ChatFormatting.GRAY, "#7b6f5a"),
    /** UNDERGROUND: an oppressive dark + tougher, bolder cave mobs. */
    THE_GLOOM("The Gloom", ChatFormatting.DARK_GRAY, "#2c3e50"),
    /** UNDERGROUND: a miner's blessing — haste and sight for deep wild diggers. */
    DEEP_RICHES("Deep Riches", ChatFormatting.GREEN, "#27ae60");

    private final String displayName;
    private final ChatFormatting color;
    private final String hex;

    NightEvent(String displayName, ChatFormatting color, String hex) {
        this.displayName = displayName;
        this.color = color;
        this.hex = hex;
    }

    /** Stable lower_snake_case key used in config, the export JSON, and the website. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return displayName;
    }

    public ChatFormatting color() {
        return color;
    }

    /** CSS hex for the website panel. */
    public String hex() {
        return hex;
    }

    public boolean isActiveEvent() {
        return this != ORDINARY;
    }

    /** All events in canonical order (ORDINARY first). */
    public static NightEvent byIndex(int i) {
        NightEvent[] v = values();
        return (i < 0 || i >= v.length) ? ORDINARY : v[i];
    }

    /** Parse a key ("blood_moon") back to the event; ORDINARY on anything unknown. */
    public static NightEvent parse(String s) {
        if (s != null) {
            String want = s.trim().toLowerCase(Locale.ROOT);
            for (NightEvent e : values()) {
                if (e.key().equals(want)) {
                    return e;
                }
            }
        }
        return ORDINARY;
    }
}
