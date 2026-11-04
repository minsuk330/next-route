package watoo.grd.nextroute.common.lock;

import java.util.concurrent.atomic.AtomicBoolean;

/** 시험용 리더 자격 — 값을 바꿔 "도중에 리더를 잃음"을 만든다. */
public final class FixedLeaderLease implements LeaderLease {

    private final AtomicBoolean leader;

    private FixedLeaderLease(boolean leader) {
        this.leader = new AtomicBoolean(leader);
    }

    public static FixedLeaderLease leader() {
        return new FixedLeaderLease(true);
    }

    public static FixedLeaderLease notLeader() {
        return new FixedLeaderLease(false);
    }

    public void set(boolean value) {
        leader.set(value);
    }

    @Override
    public boolean isLeader() {
        return leader.get();
    }

    @Override
    public long epoch() {
        return 0;
    }
}
