package com.antifly.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReboundMotionTest {
    @Test void fallingContactAllowsOneDecayingRebound() {
        ReboundMotion guard = new ReboundMotion();
        assertFalse(guard.sample(0,-2,true));
        double dy=1.8;
        for(int t=1;dy>0.05;t++) {
            assertTrue(guard.sample(t,dy,false));
            dy=(dy-0.08)*0.98;
        }
    }
    @Test void StandingOnSlimeDoesNotGrantFlight() {
        ReboundMotion guard=new ReboundMotion();
        for(int t=0;t<30;t++) assertFalse(guard.sample(t,0,true));
        assertFalse(guard.sample(30,1,true));
    }
    @Test void repeatedUpwardBoostCannotExtendRebound() {
        ReboundMotion guard=new ReboundMotion();
        guard.sample(0,-2,true);
        assertTrue(guard.sample(1,1.8,false));
        assertFalse(guard.sample(2,1.8,false));
        assertFalse(guard.sample(3,1.8,true));
    }
    @Test void proximityAloneCannotArmARebound() {
        ReboundMotion guard=new ReboundMotion();
        guard.sample(0,-2,false);
        assertFalse(guard.sample(1,1.8,true));
    }
}
