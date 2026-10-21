package com.delivery.order.application;
import com.delivery.order.application.api.IdempotencyPorts;
import com.delivery.order.application.api.LegacyIdempotencyPorts;
public final class IdempotencyWorkflow {
    private IdempotencyWorkflow() {}
    public static <L> L acquire(IdempotencyPorts<L> ports) {
        ports.requireArguments();
        L existing = ports.find();
        if (existing != null) {
            ports.assertFingerprint(existing);
            if (ports.completed(existing)) return existing;
            if (ports.ownedAndLive(existing)) return existing;
            if (ports.live(existing)) throw ports.inProgress();
        }
        if (ports.insert() == 1) return ports.requireFound();
        existing = ports.find();
        if (existing == null) throw ports.inProgress();
        ports.assertFingerprint(existing);
        if (ports.completed(existing)) return existing;
        if (ports.ownedAndLive(existing)) return existing;
        if (ports.reclaim() == 1) return ports.requireFound();
        throw ports.inProgress();
    }
    public static <L> L legacyClaim(LegacyIdempotencyPorts<L> ports) {
        L existing = ports.find();
        boolean inserted = false;
        if (existing == null && ports.insert() == 1) {
            inserted = true;
            existing = ports.requireFound();
        }
        if (existing == null) existing = ports.requireExisting();
        ports.assertFingerprint(existing);
        if (!inserted && !ports.completed(existing)) throw ports.inProgress();
        return existing;
    }
    public static <L> L claim(IdempotencyPorts<L> ports) {
        ports.requireArguments();
        L existing = ports.findLocked();
        if (existing == null) throw ports.inProgress();
        ports.assertFingerprint(existing);
        if (ports.completed(existing)) return existing;
        if (!ports.ownedAndLive(existing)) throw ports.inProgress();
        return existing;
    }
}
