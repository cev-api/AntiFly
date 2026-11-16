package com.antifly.common;

import java.util.List;

/** Names used by both the live controls and intervention diagnostics. */
public final class CheckNames {
    private CheckNames() {}

    public static final List<String> ALL = List.of(
        "air_teleport", "ground_speed", "ground_spoof_speed", "water_speed", "water_vertical",
        "ground_spoof", "air_speed", "air_vertical", "air_offset_horizontal", "air_offset_vertical",
        "air_hover", "air_nonfall", "air_time", "air_sustained", "air_flat_cruise", "air_plane",
        "air_antikick", "void_fall", "boat_speed", "vehicle_speed", "vehicle_flight",
        "elytra_no_item", "elytra_physics", "flight_toggle");
    public static final List<String> PAPER = List.of(
        "air_teleport", "ground_speed", "ground_spoof_speed", "water_speed", "water_vertical",
        "ground_spoof", "air_offset_horizontal", "air_offset_vertical", "air_hover", "air_nonfall",
        "air_sustained", "air_flat_cruise", "air_antikick", "boat_speed", "vehicle_speed",
        "vehicle_flight", "elytra_no_item", "elytra_physics", "flight_toggle");
    public static final List<String> FABRIC = List.of(
        "ground_speed", "ground_spoof_speed", "water_speed", "water_vertical", "ground_spoof",
        "air_speed", "air_vertical", "air_hover", "air_time", "air_sustained", "air_plane",
        "air_antikick", "void_fall", "boat_speed", "vehicle_speed", "vehicle_flight", "elytra_physics", "flight_toggle");

    public static String forReason(String reason) {
        if ("boat_gravity".equals(reason)) return "vehicle_flight";
        return reason.startsWith("elytra_physics_") ? "elytra_physics" : reason;
    }

    public static boolean valid(String name) {
        return ALL.contains(name);
    }
}
