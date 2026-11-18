package com.fdimo.golemsactuallyprotectvillagers.ai;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import net.minecraft.util.Mth;

public final class MantleTiming {
    public final int t1End;
    public final int t2End;
    public final int t3End;
    public final int totalTicks;

    private static volatile float cachedSpeed = -1.0F;
    private static volatile MantleTiming cachedTiming = null;

    public MantleTiming(int t1End, int t2End, int t3End, int totalTicks) {
        this.t1End = t1End;
        this.t2End = t2End;
        this.t3End = t3End;
        this.totalTicks = totalTicks;
    }

    public static MantleTiming get() {
        float speed = (Config.DATA != null && Config.DATA.ledgeClimbSpeed > 0.01F) ? Config.DATA.ledgeClimbSpeed : 0.35F;
        speed = Mth.clamp(speed, 0.05F, 5.0F);

        MantleTiming cached = cachedTiming;
        if (cached != null && cachedSpeed == speed) {
            return cached;
        }

        int d1 = Math.max(1, Math.round(3.0F / speed));
        int d2 = Math.max(2, Math.round(6.0F / speed));
        int d3 = Math.max(3, Math.round(12.0F / speed));

        int t1End = d1;
        int t2End = t1End + d2;
        int t3End = t2End + d3;
        int totalTicks = t3End; // Completes immediately upon vault touchdown without an artificial settle delay

        MantleTiming created = new MantleTiming(t1End, t2End, t3End, totalTicks);
        cachedSpeed = speed;
        cachedTiming = created;
        return created;
    }
}
