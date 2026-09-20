package com.antifly.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class ElytraPhysicsTest {
    private final ElytraPhysics.Tuning tuning = new ElytraPhysics.Tuning();

    @Test void vanillaDiveAndPullUpRemainClear() {
        ElytraPhysics verifier = new ElytraPhysics();
        ElytraPhysics.Input input = new ElytraPhysics.Input();
        double x = 0, y = 100, z = 0.6, vy = 0;
        for (int tick = 1; tick <= 140; tick++) {
            double pitch = tick < 65 ? 28 : -24;
            double[] next = ElytraPhysics.predict(x, vy, z, pitch, 0, tuning.gravity);
            x = next[0];
            vy = next[1];
            z = next[2];
            y += vy;
            input.set(x, vy, z, pitch, 0, y, tick, false, false);
            assertFalse(verifier.observe(input, tuning).actionable(tuning), "tick " + tick);
        }
    }

    @Test void constantVerticalVelocityEventuallyFlags() {
        ElytraPhysics verifier = new ElytraPhysics();
        ElytraPhysics.Input input = new ElytraPhysics.Input();
        boolean flagged = false;
        double highest = 0;
        int channels = 0;
        double y = 100;
        for (int tick = 1; tick <= 60; tick++) {
            input.set(0, 0.5, 0.8, 0, 0, y, tick, false, false);
            ElytraPhysics.Verdict verdict = verifier.observe(input, tuning);
            flagged |= verdict.actionable(tuning);
            if (verdict.confidence > highest) { highest = verdict.confidence; channels = verdict.channels; }
            y += 0.5;
        }
        assertTrue(flagged, "highest=" + highest + " channels=" + channels);
    }

    @Test void flatCruiseEventuallyFlags() {
        ElytraPhysics verifier = new ElytraPhysics();
        ElytraPhysics.Input input = new ElytraPhysics.Input();
        boolean flagged = false;
        for (int tick = 1; tick <= 60; tick++) {
            input.set(0, 0, 1.0, 0, 0, 100, tick, false, false);
            flagged |= verifier.observe(input, tuning).actionable(tuning);
        }
        assertTrue(flagged);
    }

    @Test void recordedVanillaPullUpRemainsClear() {
        // Consecutive movement samples from the clean 26.3 pull-up trace.
        // Coordinates are relative, so the test records only motion.
        double[][] motion = {
            {1.063268, 0.098329, 0.277717, -23.700},
            {1.036626, 0.124586, 0.270614, -25.050},
            {1.010300, 0.149646, 0.263613, -25.650},
            {0.984217, 0.173653, 0.256694, -26.400},
            {0.958560, 0.196203, 0.249901, -26.850},
            {0.933569, 0.216798, 0.243296, -26.850},
            {0.909227, 0.235516, 0.236873, -26.850},
            {0.885519, 0.252435, 0.230625, -26.850},
            {0.862427, 0.267626, 0.224547, -26.850},
            {0.839937, 0.281160, 0.218635, -26.850},
            {0.818032, 0.293107, 0.212882, -26.850},
            {0.796696, 0.303532, 0.207285, -26.850},
            {0.775917, 0.312499, 0.201839, -26.850},
            {0.755679, 0.320069, 0.196539, -26.850},
            {0.735968, 0.326304, 0.191380, -26.850},
            {0.716770, 0.331259, 0.186360, -26.850},
            {0.698072, 0.334991, 0.181473, -26.850}
        };
        ElytraPhysics verifier = new ElytraPhysics();
        ElytraPhysics.Input input = new ElytraPhysics.Input();
        double y = 0;
        for (int i = 0; i < motion.length; i++) {
            double[] row = motion[i];
            y += row[1];
            input.set(row[0], row[1], row[2], row[3], -75.450, y, i + 1, false, false);
            assertFalse(verifier.observe(input, tuning).actionable(tuning), "clean trace sample " + i);
        }
    }

    @Test void recordedConstantSpeedExploitFlags() {
        // The exploit trace held this displacement for over fifteen ticks.
        ElytraPhysics verifier = new ElytraPhysics();
        ElytraPhysics.Input input = new ElytraPhysics.Input();
        double y = 100;
        boolean flagged = false;
        double highest = 0;
        int channels = 0;
        for (int tick = 1; tick <= 20; tick++) {
            y -= 0.028601;
            input.set(0.991932, -0.028601, 0.025978, 0, -88.5, y, tick, false, false);
            ElytraPhysics.Verdict verdict = verifier.observe(input, tuning);
            flagged |= verdict.actionable(tuning);
            if (verdict.confidence > highest) { highest = verdict.confidence; channels = verdict.channels; }
        }
        assertTrue(flagged, "highest=" + highest + " channels=" + channels);
    }

    @Test void rocketsAndImpulsesDoNotCreateDebt() {
        ElytraPhysics verifier = new ElytraPhysics();
        ElytraPhysics.Input input = new ElytraPhysics.Input();
        for (int tick = 1; tick <= 80; tick++) {
            input.set(0, 1.8, 3.0, -30, 0, 100 + tick, tick, true, false);
            assertFalse(verifier.observe(input, tuning).actionable(tuning));
        }
        input.set(0, 1.8, 3.0, -30, 0, 181, 81, false, true);
        assertFalse(verifier.observe(input, tuning).actionable(tuning));
    }

    @Test void samplesAtSameTickAreIgnored() {
        ElytraPhysics verifier = new ElytraPhysics();
        ElytraPhysics.Input input = new ElytraPhysics.Input();
        for (int i = 0; i < 50; i++) {
            input.set(0, 0.5, 1, 0, 0, 100, 1, false, false);
            assertFalse(verifier.observe(input, tuning).actionable(tuning));
        }
    }
}
