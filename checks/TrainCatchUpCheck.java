import mtr.data.TrainCatchUp;

/**
 * Checks the closed form against a forward simulation of the run it describes.
 *
 * The simulation is deliberately naive — accelerate until braking is the only way to stop in what is left, then
 * brake — because that is what the movement loop actually does with a speed cap. If the two disagree, the closed
 * form is wrong.
 */
public class TrainCatchUpCheck {

    /** Ticks to cover `distance` from `u`, capped at `peak`, ending stopped. Sub-stepped so discretisation is not the error. */
    private static double simulate(double distance, double u, double peak, double a) {
        final double step = 0.001;
        double travelled = 0, v = u, t = 0;
        while (travelled < distance) {
            final double remaining = distance - travelled;
            // Brake once stopping is all there is time for
            if (v > 0 && remaining <= v * v / (2 * a)) {
                v = Math.max(0, v - a * step);
            } else {
                v = Math.min(peak, v + a * step);
            }
            if (v <= 0) break;
            travelled += v * step;
            t += step;
            if (t > 1e6) break;
        }
        return t;
    }

    private static void near(double actual, double expected, double tolerance, String what) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(what + ": expected ~" + expected + " but got " + actual);
        }
    }

    public static void main(String[] args) {
        // A train already at speed, with room to spare: the peak it is told to reach must land it on time
        // distance, ticks, start speed, acceleration. Each is comfortably inside what is reachable: the most a
        // train can cover in T from rest and still stop is aT²/4, which is a lot less than it first looks.
        for (double[] c : new double[][] {
                {150, 240, 0.5, 0.01},
                {100, 240, 0.0, 0.01},
                {600, 400, 0.3, 0.02},
                {12, 120, 0.0, 0.005},
                {2000, 600, 1.0, 0.03},
        }) {
            final double d = c[0], t = c[1], u = c[2], a = c[3];
            final float v = TrainCatchUp.peakSpeedToArriveIn(d, t, (float) u, (float) a);
            if (v == Float.MAX_VALUE) throw new AssertionError("declared impossible but should be doable: d=" + d);
            near(simulate(d, u, v, a), t, t * 0.02, "d=" + d + " t=" + t + " u=" + u);
        }

        // The shortest possible run is straight up and straight down again: peak aT/2 over distance aT²/4
        final double a = 0.01, t = 200;
        near(TrainCatchUp.peakSpeedToArriveIn(a * t * t / 4, t, 0, (float) a), a * t / 2, 1e-3, "triangular profile");

        // One block further than that in the same time cannot be done at any speed
        if (TrainCatchUp.peakSpeedToArriveIn(a * t * t / 4 + 50, t, 0, (float) a) != Float.MAX_VALUE) {
            throw new AssertionError("claimed to make a run that is not physically possible");
        }

        // Very high acceleration collapses to the naive average, which is the only case where distance/time was right
        near(TrainCatchUp.peakSpeedToArriveIn(600, 240, 0, 1000f), 600.0 / 240, 1e-3, "instant acceleration");

        // The peak always exceeds the average, because the run spends time below it at both ends
        final float v = TrainCatchUp.peakSpeedToArriveIn(150, 240, 0.5f, 0.01f);
        if (v <= 150.0 / 240) throw new AssertionError("peak must exceed the average, got " + v);

        // Degenerate inputs return 0 rather than a number the caller would act on
        for (double[] bad : new double[][] {{0, 240, 0, 0.01}, {600, 0, 0, 0.01}, {600, 240, 0, 0}, {-5, 240, 0, 0.01}}) {
            if (TrainCatchUp.peakSpeedToArriveIn(bad[0], bad[1], (float) bad[2], (float) bad[3]) != 0) {
                throw new AssertionError("degenerate input did not return 0");
            }
        }

        System.out.println("TrainCatchUp ok");
    }
}
