package com.antifly.common;

/** Tick-based checks independent of client-reported velocity and grounded flags. */
public final class MountMotion {
    private final ReboundMotion rebound = new ReboundMotion();
    private double speedExcess;
    private double dashAllowance;
    private long nextDashTick;
    private boolean wasDashing;
    private long lastTick = Long.MIN_VALUE;
    private int airborneTicks;
    private int fallMismatchWindow;
    private double previousDy;
    private double airborneHeight;
    private int graceTicks;

    public enum Result { OK, SPEED, FLIGHT }

    public int airborneTicks() { return airborneTicks; }
    public int graceTicks() { return graceTicks; }

    public Result sample(long tick, double horizontal, double dy, boolean supported,
                         double speedLimit, double jumpVelocity, int airGrace,
                         boolean dash, double dashBoost, int dashCooldown) {
        return sample(tick, horizontal, dy, supported, speedLimit, jumpVelocity, airGrace,
            dash, dashBoost, dashCooldown, false);
    }

    public Result sample(long tick, double horizontal, double dy, boolean supported,
                         double speedLimit, double jumpVelocity, int airGrace,
                         boolean dash, double dashBoost, int dashCooldown, boolean bounceContact) {
        return sample(tick, horizontal, dy, supported, speedLimit, jumpVelocity, airGrace,
            dash, dashBoost, dashCooldown, bounceContact, true, true);
    }

    public Result sample(long tick, double horizontal, double dy, boolean supported,
                         double speedLimit, double jumpVelocity, int airGrace,
                         boolean dash, double dashBoost, int dashCooldown, boolean bounceContact,
                         boolean speedEnabled, boolean flightEnabled) {
        if (tick == lastTick) return Result.OK;
        lastTick = tick;
        if (dash && !wasDashing && tick >= nextDashTick) {
            dashAllowance = dashBoost;
            nextDashTick = tick + dashCooldown;
        }
        wasDashing = dash;
        double allowed = speedLimit + dashAllowance;
        dashAllowance *= 0.9;
        speedExcess = Math.max(0, speedExcess + horizontal - allowed);
        if (!speedEnabled) speedExcess = 0;
        // Accumulate small speed gains as well as rejecting large position jumps.
        if (speedEnabled && (horizontal > allowed * 2 + 0.5 || speedExcess > 0.5)) return Result.SPEED;
        boolean bouncing = rebound.sample(tick, dy, bounceContact);
        if (bouncing) {
            airborneTicks = 0;
            fallMismatchWindow = 0;

        }
        // A rise alone is not flight: it can be terrain climbing. Flight
        // requires a sustained unsupported interval. Speed is checked above
        // regardless of support, climbing, or rebound state.
        if (supported) {
            airborneTicks = 0;
            fallMismatchWindow = 0;
            airborneHeight = 0;

        } else {
            if (!flightEnabled) {
                airborneTicks = 0;
                fallMismatchWindow = 0;
                airborneHeight = 0;
                previousDy = dy;
                return Result.OK;
            }
            airborneTicks++;
            airborneHeight += dy;
            int grace = Math.max(airGrace, (int)Math.ceil(jumpVelocity / 0.08) * 2 + 8);
            graceTicks = grace;
            if (flightEnabled && airborneTicks > Math.max(40, grace + 8) && airborneHeight > -0.5) return Result.FLIGHT;
            // Once a jump's grace ends, shallow constant descent and repeated
            // upward boosts must not reset the flight window. Terminal falls
            // remain valid. A rolling evidence window tolerates isolated collision jitter.
            if (airborneTicks > grace) {
                boolean mismatch = dy > -3.0 && dy > (previousDy - 0.08) * 0.98 + 0.035;
                fallMismatchWindow = ((fallMismatchWindow << 1) | (mismatch ? 1 : 0)) & 0xfff;
                if (flightEnabled && Integer.bitCount(fallMismatchWindow) >= 6) return Result.FLIGHT;
            }

        }
        previousDy = dy;
        return Result.OK;
    }
}
