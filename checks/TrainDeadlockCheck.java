import mtr.data.TrainDeadlock;

/** One property: of two trains each waiting on the other, exactly one proceeds. */
public class TrainDeadlockCheck {
    public static void main(String[] args) {
        long[] ids = {Long.MIN_VALUE, -7L, -1L, 0L, 1L, 42L, Long.MAX_VALUE};
        int pairs = 0;
        for (long a : ids) {
            for (long b : ids) {
                if (a == b) continue;
                boolean aGoes = TrainDeadlock.proceeds(a, b, true);
                boolean bGoes = TrainDeadlock.proceeds(b, a, true);
                if (aGoes == bGoes) throw new AssertionError("both or neither proceed: " + a + " " + b);
                // A train that is not waiting on this one is never passed, whatever the ids are
                if (TrainDeadlock.proceeds(a, b, false)) throw new AssertionError("passed a train not waiting on this one");
                pairs++;
            }
        }
        System.out.println("TrainDeadlock ok (" + pairs + " pairs, including id 0)");
    }
}
