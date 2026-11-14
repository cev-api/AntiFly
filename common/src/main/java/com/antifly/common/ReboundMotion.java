package com.antifly.common;

/** A falling contact permits one decaying upward rebound, never a full exemption. */
public final class ReboundMotion {
    private long lastTick = Long.MIN_VALUE;
    private long armedUntil = Long.MIN_VALUE;
    private double upwardLimit;
    private boolean ascending;
    private boolean valid;

    public boolean sample(long tick, double dy, boolean bounceContact) {
        if (tick == lastTick) return valid;
        lastTick = tick;
        valid = false;
        if (dy < -0.1 && bounceContact) {
            upwardLimit = -dy;
            armedUntil = tick + 2;
            ascending = false;
        } else if (dy > 0.05 && (ascending || tick <= armedUntil)) {
            if (dy <= upwardLimit + 0.05) {
                valid = true;
                ascending = true;
                upwardLimit = Math.max(0, (dy - 0.08) * 0.98);
                armedUntil = Long.MIN_VALUE;
            } else {
                ascending = false;
                armedUntil = Long.MIN_VALUE;
            }
        } else if (dy <= 0.05) {
            ascending = false;
        }
        return valid;
    }
}
