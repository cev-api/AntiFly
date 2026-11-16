package com.antifly.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MountMotionTest {
    @Test void disablingSpeedDoesNotMaskFlight() {
        MountMotion guard = new MountMotion();
        MountMotion.Result result = MountMotion.Result.OK;
        for (int t = 0; t < 40; t++) {
            result = guard.sample(t, 4, 0, false, 0.75, 1, 24, false, 0, 40, false, false, true);
            if (result == MountMotion.Result.FLIGHT) break;
        }
        assertEquals(MountMotion.Result.FLIGHT, result);
    }

    @Test void disablingFlightKeepsSpeedChecksAndClearsStaleEvidence() {
        MountMotion guard = new MountMotion();
        for (int t = 0; t < 100; t++) {
            assertEquals(MountMotion.Result.OK,
                guard.sample(t, 0, 0, false, 0.75, 1, 24, false, 0, 40, false, true, false));
        }
        assertEquals(MountMotion.Result.OK,
            guard.sample(100, 0, 0, false, 0.75, 1, 24, false, 0, 40, false, true, true));
        assertEquals(MountMotion.Result.SPEED,
            guard.sample(101, 4, 0, false, 0.75, 1, 24, false, 0, 40, false, true, false));
    }

    private MountMotion.Result sample(MountMotion guard, int tick, double x, double y, boolean support) {
        return guard.sample(tick, x, y, support, 0.75, 1.0, 24, false, 0, 40);
    }
    @Test void normalRidingRemainsSupportedForLongJourneys() {
        MountMotion guard = new MountMotion();
        for (int t = 0; t < 1000; t++) assertEquals(MountMotion.Result.OK, sample(guard,t,0.7,0,true));
    }
    @Test void groundAndFluidSupportDoNotExcuseSpeed() {
        MountMotion guard = new MountMotion();
        assertEquals(MountMotion.Result.SPEED, sample(guard,0,3,0,true));
    }
    @Test void smallPersistentSpeedExcessAccumulates() {
        MountMotion guard = new MountMotion();
        MountMotion.Result result = null;
        for (int t=0;t<20;t++) result=sample(guard,t,0.8,0,true);
        assertEquals(MountMotion.Result.SPEED,result);
    }
    @Test void legitimateJumpAndFastDescentAreAccepted() {
        MountMotion guard = new MountMotion();
        sample(guard,0,0.7,0,true);
        double dy=1;
        for (int t=1;t<100;t++) {
            assertEquals(MountMotion.Result.OK,sample(guard,t,0.7,dy,false));
            dy=(dy-0.08)*0.98;
        }
    }
    @Test void shallowGlideCannotResetFlightWindow() {
        MountMotion guard = new MountMotion();
        MountMotion.Result result=null;
        for(int t=0;t<40;t++) result=sample(guard,t,0.7,-0.04,false);
        assertEquals(MountMotion.Result.FLIGHT,result);
    }
    @Test void hoveringAndRepeatedBoostsAreRejected() {
        MountMotion guard = new MountMotion();
        MountMotion.Result result=null;
        for(int t=0;t<40;t++) result=sample(guard,t,0,0,false);
        assertEquals(MountMotion.Result.FLIGHT,result);
        MountMotion rising = new MountMotion();
        for(int t=0;t<80;t++) result=sample(rising,t,0,2,false);
        assertEquals(MountMotion.Result.FLIGHT,result);
    }
    @Test void dashAllowsBurstButCannotBeHeldForPermanentSpeed() {
        MountMotion guard = new MountMotion();
        assertEquals(MountMotion.Result.OK,guard.sample(0,1.5,0,true,0.75,1,24,true,1.2,40));
        MountMotion.Result result=null;
        for(int t=1;t<80;t++) result=guard.sample(t,1.5,0,true,0.75,1,24,true,1.2,40);
        assertEquals(MountMotion.Result.SPEED,result);
    }
    @Test void mountBounceKeepsSpeedEnforcement() {
        MountMotion guard=new MountMotion();
        assertEquals(MountMotion.Result.OK,guard.sample(0,0.7,-3,true,0.75,1,24,false,0,40,true));
        assertEquals(MountMotion.Result.OK,guard.sample(1,0.7,2.8,false,0.75,1,24,false,0,40,false));
        assertEquals(MountMotion.Result.SPEED,guard.sample(2,4,2.6,false,0.75,1,24,false,0,40,false));
    }
    @Test void bobbingWithSmallNetDescentStillFails() {
        MountMotion guard=new MountMotion();
        boolean failed=false;
        for(int t=0;t<80;t++) {
            if(sample(guard,t,0.7,t%2==0 ? -0.3 : 0.2,false)==MountMotion.Result.FLIGHT) failed=true;
        }
        assertTrue(failed);
    }
    @Test void supportedTerrainRiseDoesNotCountAsFlight() {
        assertEquals(MountMotion.Result.OK,sample(new MountMotion(),0,0.5,5,true));
    }
    @Test void legitimateStepUpIsAccepted() {
        assertEquals(MountMotion.Result.OK,sample(new MountMotion(),0,0.7,1,true));
    }
    @Test void lowJumpHorseCanStepOntoVerifiedTerrain() {
        MountMotion guard = new MountMotion();
        assertEquals(MountMotion.Result.OK, guard.sample(0,0.5,0,true,0.75,0.7,24,false,0,40));
        assertEquals(MountMotion.Result.OK, guard.sample(1,0.5,1,true,0.75,0.7,24,false,0,40));
        assertEquals(MountMotion.Result.OK, guard.sample(2,0.5,0,true,0.75,0.7,24,false,0,40));
    }
    @Test void climbingMultipleBlocksWithIntermittentSupportDoesNotCountAsFlight() {
        MountMotion guard = new MountMotion();
        for(int t=0;t<200;t++) {
            // Foot support can be missing during a step transition. Actual
            // terrain contact recurs as the horse climbs the hillside.
            boolean supported=t%3==0;
            assertEquals(MountMotion.Result.OK,guard.sample(t,0.5,supported?0:1,
                supported,0.75,0.7,24,false,0,40));
        }
    }
    @Test void sameClimbInOpenAirEventuallyFails() {
        MountMotion guard = new MountMotion();
        MountMotion.Result result=null;
        for(int t=0;t<80;t++) result=guard.sample(t,0.5,t%3==0?0:1,false,0.75,0.7,24,false,0,40);
        assertEquals(MountMotion.Result.FLIGHT,result);
    }
    @Test void stepSupportDoesNotExcuseSpeed() {
        assertEquals(MountMotion.Result.SPEED,
            new MountMotion().sample(0,3,1,true,0.75,0.7,24,false,0,40));
    }
    @Test void duplicatePacketsDoNotAgeTheFlightWindow() {
        MountMotion guard=new MountMotion();
        for(int n=0;n<100;n++) assertEquals(MountMotion.Result.OK,sample(guard,1,0,0,false));
    }
}
