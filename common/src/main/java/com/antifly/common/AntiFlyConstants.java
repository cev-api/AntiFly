package com.antifly.common;

public final class AntiFlyConstants {
    public static final int MAX_AIR_TICKS = 12;
    public static final double AIR_DESCENT_EPSILON = -0.02;
    public static final double SUPPORT_EPSILON = 0.03;
    public static final double SUPPORT_LOOSE_EPSILON = 0.20;
    public static final double HOVER_DELTA_Y_EPSILON = 0.001;
    public static final int HOVER_TICKS = 6;
    public static final int GROUND_SPOOF_TICKS = 3;
    public static final int VOID_FALL_TICKS = 8;
    public static final double VOID_Y_OFFSET = 2.0;
    public static final int GLIDE_GROUND_GRACE_TICKS = 8;
    public static final double ELYTRA_MAX_HORIZONTAL = 2.6;
    public static final double ELYTRA_MAX_UP = 0.55;
    public static final double ELYTRA_MAX_DOWN = 4.0;
    public static final double ELYTRA_STALL_HORIZONTAL_MAX = 0.05;
    public static final double ELYTRA_STALL_VERTICAL_MAX = 0.05;
    public static final int ELYTRA_STALL_TICKS = 10;
    public static final double ELYTRA_SLOWDOWN_MIN_SPEED = 0.45;
    public static final double ELYTRA_SLOWDOWN_MIN_SCALE = 0.35;
    public static final int ELYTRA_SLOWDOWN_GRACE_TICKS = 8;
    public static final double BASE_GROUND_MAX = 0.35;
    public static final double GROUND_BUFFER = 0.10;
    public static final double BASE_WATER_MAX = 0.45;
    public static final double WATER_BUFFER = 0.10;
    public static final double WATER_VERTICAL_MAX = 0.60;
    public static final double WATER_VERTICAL_BUFFER = 0.10;
    public static final double BASE_AIR_MAX = 0.65;
    public static final double AIR_BUFFER = 0.15;
    public static final double BASE_AIR_VERTICAL_MAX = 0.55;
    public static final double AIR_VERTICAL_BUFFER = 0.10;

    private AntiFlyConstants() {
    }
}
