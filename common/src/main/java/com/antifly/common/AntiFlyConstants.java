package com.antifly.common;

public final class AntiFlyConstants {
    public static final int MAX_AIR_TICKS = 12;
    public static final int AIR_NON_FALL_TICKS = 20;
    public static final int ANTI_KICK_WINDOW_TICKS = 40;
    public static final double ANTI_KICK_MIN_DESCENT = 0.35;
    public static final double AIR_DESCENT_EPSILON = -0.02;
    public static final double SUPPORT_EPSILON = 0.03;
    public static final double SUPPORT_LOOSE_EPSILON = 0.20;
    public static final double SUPPORT_TALL_BLOCK_DEPTH = 0.55;
    public static final double HOVER_DELTA_Y_EPSILON = 0.001;
    public static final double HOVER_HORIZONTAL_EPSILON = 0.03;
    public static final int HOVER_TICKS = 6;
    public static final int GROUND_SPOOF_TICKS = 3;
    public static final int VOID_FALL_TICKS = 8;
    public static final double VOID_Y_OFFSET = 2.0;
    public static final int GLIDE_GROUND_GRACE_TICKS = 8;
    public static final int VEHICLE_AIR_GRACE_TICKS = 10;
    public static final int VEHICLE_AIR_GRACE_TICKS_HORSE = 24;
    public static final double VEHICLE_FALL_MIN_DESCENT = -0.04;
    public static final double VEHICLE_FALL_MAX_HORIZONTAL = 0.40;
    public static final int VEHICLE_FALL_TICKS_MAX = 60;
    public static final double BOAT_MAX_HORIZONTAL = 0.85;
    public static final double ELYTRA_MAX_HORIZONTAL = 6.0;
    public static final double ELYTRA_MAX_UP = 4.0;
    public static final double ELYTRA_MAX_DOWN = 10.0;
    public static final double ELYTRA_STALL_HORIZONTAL_MAX = 0.05;
    public static final double ELYTRA_STALL_VERTICAL_MAX = 0.05;
    public static final int ELYTRA_STALL_TICKS = 10;
    public static final double ELYTRA_SLOWDOWN_MIN_SPEED = 0.45;
    public static final double ELYTRA_SLOWDOWN_MIN_SCALE = 0.35;
    public static final int ELYTRA_SLOWDOWN_GRACE_TICKS = 8;
    public static final double BASE_GROUND_MAX = 0.35;
    public static final double GROUND_BUFFER = 0.10;
    public static final double DEFAULT_GROUND_WALK_MAX = 0.67;
    public static final double DEFAULT_GROUND_MOUNT_MAX = 0.750;
    public static final double DEFAULT_AIR_MAX = 1.8;
    public static final double DEFAULT_AIR_VERTICAL_MAX = 1.0;
    public static final double DEFAULT_WATER_MAX = 0.55;
    public static final double DEFAULT_WATER_VERTICAL_MAX = 0.7;
    public static final double BASE_WATER_MAX = 0.45;
    public static final double WATER_BUFFER = 0.10;
    public static final double WATER_VERTICAL_MAX = 0.62;
    public static final double WATER_VERTICAL_BUFFER = 0.10;
    public static final double BASE_AIR_MAX = 0.65;
    public static final double AIR_BUFFER = 0.15;
    public static final double BASE_AIR_VERTICAL_MAX = 0.55;
    public static final double AIR_VERTICAL_BUFFER = 0.10;

    // External impulses (wind charges, TNT/bed explosions, mace smashes, mob
    // knockback) yank the player far harder than any legitimate input can in a
    // single tick. A vanilla jump only gains 0.42 blocks/tick, so a gain above
    // IMPULSE_MIN_VELOCITY_GAIN that also leaves the player moving faster than
    // IMPULSE_MIN_RESULTING_VELOCITY can only come from outside the client.
    // Landing produces a velocity LOSS, so it never qualifies.
    public static final double IMPULSE_MIN_VELOCITY_GAIN = 0.50;
    public static final double IMPULSE_MIN_RESULTING_VELOCITY = 0.60;
    public static final int IMPULSE_GRACE_TICKS = 20;

    // A one-shot stopFallFlying() is undone by a hacked client that simply
    // re-sends START_FALL_FLYING on the next tick, so a violation has to
    // suppress gliding for a while instead. Refusing the re-deploy is what
    // turns detection into an actual penalty.
    public static final int ELYTRA_GLIDE_SUPPRESSION_TICKS = 60;

    // A vanilla glide that climbs does so by converting momentum, so the climb
    // rate DECAYS within a few ticks (measured: +1.245, +0.719, +0.024, then
    // negative). A hack that writes velocity directly holds a constant climb
    // forever. Sustained climb is therefore the discriminator that a net
    // altitude threshold alone cannot provide.
    public static final double ELYTRA_SUSTAINED_CLIMB_MIN_DELTA_Y = 0.20;
    public static final int ELYTRA_SUSTAINED_CLIMB_TICKS = 60;

    // Hover detection suppresses itself only when the player is this close to
    // real ground. The wider isNearGround offset left a band where a stationary
    // hoverer was airborne but never counted as hovering.
    public static final double HOVER_GROUND_SUPPRESSION_DEPTH = 0.08;

    // Water movement scales with the vanilla speed modifiers, exactly like the
    // ground check already does for sprinting and Speed potions.
    public static final double WATER_SPEED_DEPTH_STRIDER_PER_LEVEL = 0.20;
    public static final double WATER_SPEED_DOLPHINS_GRACE = 0.40;

    // Touching down must not erase an accumulated flight-damage timer, but it
    // should still recover faster than the timer built up.
    public static final double DAMAGE_TIMER_RECOVERY_MULTIPLIER = 2.0;

    // A setback must not clear the running violation count, otherwise a player
    // who is corrected and immediately continues looks identical to a
    // first-time offender in the logs.
    public static final long REPEAT_OFFENDER_WINDOW_MS = 300_000L;
    public static final long REPEAT_OFFENDER_COOLDOWN_MS = 60_000L;

    private AntiFlyConstants() {
    }
}
