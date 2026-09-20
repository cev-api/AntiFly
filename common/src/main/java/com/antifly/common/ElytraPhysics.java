package com.antifly.common;

import java.util.Locale;

/** A conservative, tick-sampled model of unpowered vanilla Elytra movement. */
public final class ElytraPhysics {
    public enum Mode {
        OFF, OBSERVE, ENFORCE;

        public static Mode parse(String value) {
            if (value == null) return OBSERVE;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "off", "disabled" -> OFF;
                case "enforce", "on" -> ENFORCE;
                default -> OBSERVE;
            };
        }
    }

    /** Server owners may widen these values for altered physics or packet timing. */
    public static final class Tuning {
        public double gravity = 0.08;
        public double residualAllowance = 0.20;
        public double residualPerSpeed = 0.02;
        public double energyAllowance = 0.04;
        public double steadySpeedDeviation = 0.008;
        public double minimumSteadySpeed = 0.45;
        public int steadyWindowTicks = 14;
        public int evidenceWindowTicks = 20;
        public int minimumEvidenceTicks = 8;
        public int minimumChannels = 2;
        public double confidenceThreshold = 0.45;
        public double sharpTurnDegrees = 50.0;

        public boolean update(String key, double value) {
            if (!Double.isFinite(value) || value < 0) return false;
            switch (key) {
                case "elytraVerifierGravity" -> gravity = value;
                case "elytraVerifierResidualAllowance" -> residualAllowance = value;
                case "elytraVerifierResidualPerSpeed" -> residualPerSpeed = value;
                case "elytraVerifierEnergyAllowance" -> energyAllowance = value;
                case "elytraVerifierSteadySpeedDeviation" -> steadySpeedDeviation = Math.max(0.0001, value);
                case "elytraVerifierMinimumSteadySpeed" -> minimumSteadySpeed = value;
                case "elytraVerifierSteadyWindowTicks" -> steadyWindowTicks = Math.max(2, (int) Math.round(value));
                case "elytraVerifierEvidenceWindowTicks" -> evidenceWindowTicks = Math.max(2, (int) Math.round(value));
                case "elytraVerifierMinimumEvidenceTicks" -> minimumEvidenceTicks = Math.max(1, (int) Math.round(value));
                case "elytraVerifierMinimumChannels" -> minimumChannels = Math.max(1, (int) Math.round(value));
                case "elytraVerifierConfidenceThreshold" -> confidenceThreshold = value;
                case "elytraVerifierSharpTurnDegrees" -> sharpTurnDegrees = value;
                default -> { return false; }
            }
            return true;
        }

        public String value(String key) {
            return switch (key) {
                case "elytraVerifierGravity" -> String.valueOf(gravity);
                case "elytraVerifierResidualAllowance" -> String.valueOf(residualAllowance);
                case "elytraVerifierResidualPerSpeed" -> String.valueOf(residualPerSpeed);
                case "elytraVerifierEnergyAllowance" -> String.valueOf(energyAllowance);
                case "elytraVerifierSteadySpeedDeviation" -> String.valueOf(steadySpeedDeviation);
                case "elytraVerifierMinimumSteadySpeed" -> String.valueOf(minimumSteadySpeed);
                case "elytraVerifierSteadyWindowTicks" -> String.valueOf(steadyWindowTicks);
                case "elytraVerifierEvidenceWindowTicks" -> String.valueOf(evidenceWindowTicks);
                case "elytraVerifierMinimumEvidenceTicks" -> String.valueOf(minimumEvidenceTicks);
                case "elytraVerifierMinimumChannels" -> String.valueOf(minimumChannels);
                case "elytraVerifierConfidenceThreshold" -> String.valueOf(confidenceThreshold);
                case "elytraVerifierSharpTurnDegrees" -> String.valueOf(sharpTurnDegrees);
                default -> null;
            };
        }
    }

    public static final class Input {
        public double vx, vy, vz, pitch, yaw, y;
        public long tick;
        public boolean powered, disturbed;

        public void set(double vx, double vy, double vz, double pitch, double yaw, double y,
                        long tick, boolean powered, boolean disturbed) {
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.pitch = pitch;
            this.yaw = yaw;
            this.y = y;
            this.tick = tick;
            this.powered = powered;
            this.disturbed = disturbed;
        }
    }

    public static final class Verdict {
        public double confidence, evidence;
        public int channels;
        public String reason;

        public boolean actionable(Tuning tuning) {
            return channels >= tuning.minimumChannels && confidence >= tuning.confidenceThreshold;
        }
    }

    private static final int CAPACITY = 40;
    private final double[] speeds = new double[CAPACITY];
    private final double[] residuals = new double[CAPACITY];
    private final double[] energyExcess = new double[CAPACITY];
    private final double[] turnExcess = new double[CAPACITY];
    private final Verdict verdict = new Verdict();
    private int samples, cursor;
    private long lastTick = Long.MIN_VALUE;
    private double lastVx, lastVy, lastVz;

    public Verdict observe(Input input, Tuning tuning) {
        if (input.powered || input.disturbed || !finite(input.vx, input.vy, input.vz, input.pitch, input.yaw, input.y)
            || lastTick != Long.MIN_VALUE && (input.tick < lastTick || input.tick - lastTick > 1)) {
            reset();
        }
        if (input.tick <= lastTick) return verdict;

        double speed = Math.sqrt(input.vx * input.vx + input.vy * input.vy + input.vz * input.vz);
        double residual = 0, excessEnergy = 0, turn = 0;
        if (samples > 0 && !input.powered && !input.disturbed) {
            double[] predicted = predict(lastVx, lastVy, lastVz, input.pitch, input.yaw, tuning.gravity);
            double error = Math.sqrt(square(input.vx - predicted[0]) + square(input.vy - predicted[1])
                + square(input.vz - predicted[2]));
            residual = Math.max(0, error - 0.015 - tuning.residualPerSpeed * speed);

            // Compare with the energy the vanilla step predicts, rather than
            // assuming E = g*y + v^2/2 is conserved by Elytra lift and drag.
            double actualE = tuning.gravity * input.y + 0.5 * speed * speed;
            double predictedE = tuning.gravity * input.y
                + 0.5 * (square(predicted[0]) + square(predicted[1]) + square(predicted[2]));
            excessEnergy = Math.max(0, actualE - predictedE);

            double oldSpeed = Math.sqrt(square(lastVx) + square(lastVy) + square(lastVz));
            if (oldSpeed > 0.5 && speed > 0.5) {
                double dot = (lastVx * input.vx + lastVy * input.vy + lastVz * input.vz) / (oldSpeed * speed);
                turn = Math.max(0, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))))
                    - tuning.sharpTurnDegrees);
            }
        }

        cursor = (cursor + 1) % CAPACITY;
        speeds[cursor] = speed;
        residuals[cursor] = residual;
        energyExcess[cursor] = excessEnergy;
        turnExcess[cursor] = turn;
        samples = Math.min(CAPACITY, samples + 1);
        lastVx = input.vx;
        lastVy = input.vy;
        lastVz = input.vz;
        lastTick = input.tick;

        verdict.confidence = 0;
        verdict.channels = 0;
        verdict.evidence = 0;
        verdict.reason = null;
        int window = Math.min(samples, Math.max(1, Math.min(CAPACITY, tuning.evidenceWindowTicks)));
        if (window < Math.max(2, tuning.minimumEvidenceTicks) || input.powered || input.disturbed) return verdict;

        int residualTicks = 0, energyTicks = 0, turnTicks = 0;
        double totalEnergy = 0, totalResidual = 0;
        for (int i = 0; i < window; i++) {
            int at = (cursor - i + CAPACITY) % CAPACITY;
            if (residuals[at] > 0.005) residualTicks++;
            if (energyExcess[at] > 0.003) energyTicks++;
            if (turnExcess[at] > 0) turnTicks++;
            totalEnergy += energyExcess[at];
            totalResidual += residuals[at];
        }
        double residualScore = totalResidual > tuning.residualAllowance
            && residualTicks >= tuning.minimumEvidenceTicks
            ? Math.min(1, (totalResidual - tuning.residualAllowance) / Math.max(0.01, tuning.residualAllowance)) : 0;
        double energyScore = totalEnergy > tuning.energyAllowance && energyTicks >= tuning.minimumEvidenceTicks
            ? Math.min(1, (totalEnergy - tuning.energyAllowance) / Math.max(0.01, tuning.energyAllowance)) : 0;
        double steadyScore = 0;
        int steadyWindow = Math.max(2, Math.min(CAPACITY, tuning.steadyWindowTicks));
        if (samples >= steadyWindow && speed >= tuning.minimumSteadySpeed) {
            double mean = 0;
            for (int i = 0; i < steadyWindow; i++) mean += speeds[(cursor - i + CAPACITY) % CAPACITY];
            mean /= steadyWindow;
            double variance = 0;
            for (int i = 0; i < steadyWindow; i++) {
                variance += square(speeds[(cursor - i + CAPACITY) % CAPACITY] - mean);
            }
            double deviation = Math.sqrt(variance / steadyWindow);
            steadyScore = Math.max(0, 1 - deviation / Math.max(0.0001, tuning.steadySpeedDeviation));
        }
        double turnScore = turnTicks >= 3 ? Math.min(1, turnTicks / 5.0) : 0;
        double[] scores = {residualScore, energyScore, steadyScore, turnScore};
        String[] names = {"physics_residual", "energy_creation", "constant_velocity", "impossible_turn"};
        double strongest = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] <= 0) continue;
            verdict.channels++;
            verdict.confidence += scores[i] / scores.length;
            if (scores[i] > strongest) {
                strongest = scores[i];
                verdict.reason = names[i];
                verdict.evidence = scores[i];
            }
        }
        return verdict;
    }

    /** The unpowered step in LivingEntity.updateFallFlyingMovement (26.3). */
    public static double[] predict(double vx, double vy, double vz, double pitch, double yaw, double gravity) {
        double p = Math.toRadians(pitch);
        double a = Math.toRadians(yaw);
        double lookX = -Math.sin(a) * Math.cos(p);
        double lookZ = Math.cos(a) * Math.cos(p);
        double lookHorizontal = Math.hypot(lookX, lookZ);
        double horizontal = Math.hypot(vx, vz);
        double cosine = square(Math.cos(p));
        vy -= gravity * (1 - cosine * 0.75);
        if (vy < 0 && lookHorizontal > 0) {
            double lift = -vy * 0.1 * cosine;
            vx += lookX * lift / lookHorizontal;
            vy += lift;
            vz += lookZ * lift / lookHorizontal;
        }
        if (p < 0 && lookHorizontal > 0) {
            double climb = horizontal * -Math.sin(p) * 0.04;
            vx -= lookX * climb / lookHorizontal;
            vy += climb * 3.2;
            vz -= lookZ * climb / lookHorizontal;
        }
        if (lookHorizontal > 0) {
            vx += (lookX / lookHorizontal * horizontal - vx) * 0.1;
            vz += (lookZ / lookHorizontal * horizontal - vz) * 0.1;
        }
        return new double[] {vx * 0.99, vy * 0.98, vz * 0.99};
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static double square(double value) { return value * value; }

    public void reset() {
        samples = 0;
        cursor = 0;
        lastTick = Long.MIN_VALUE;
        verdict.confidence = 0;
        verdict.channels = 0;
        verdict.evidence = 0;
        verdict.reason = null;
    }
}
