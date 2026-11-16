package com.antifly.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CheckNamesTest {
    @Test void verifierReasonsShareTheSwitchShownToAdministrators() {
        for (String reason : new String[] {"physics_residual", "energy_creation", "constant_velocity", "impossible_turn"}) {
            String name = CheckNames.forReason("elytra_physics_" + reason);
            assertEquals("elytra_physics", name);
            assertTrue(CheckNames.PAPER.contains(name));
            assertTrue(CheckNames.FABRIC.contains(name));
        }
    }

    @Test void everyAdvertisedNameIsRecognized() {
        assertTrue(CheckNames.PAPER.stream().allMatch(CheckNames::valid));
        assertTrue(CheckNames.FABRIC.stream().allMatch(CheckNames::valid));
        assertEquals(CheckNames.PAPER.size(), CheckNames.PAPER.stream().distinct().count());
        assertEquals(CheckNames.FABRIC.size(), CheckNames.FABRIC.stream().distinct().count());
    }
}
