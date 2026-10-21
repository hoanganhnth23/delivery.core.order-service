package com.delivery.order.application;
import com.delivery.order.application.api.CreateOrderPorts;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
class CreateOrderWorkflowTest {
    static class Ports implements CreateOrderPorts<String, String, String> {
        List<String> calls = new ArrayList<>();
        boolean key = true, completed, nullLease, nullToken, releaseFailure;
        String fail;
        RuntimeException failure = new RuntimeException("original");
        UUID token = UUID.randomUUID();
        void call(String name) { calls.add(name); if (name.equals(fail)) throw failure; }
        public void admit() { call("admit"); }
        public boolean hasIdempotencyKey() { return key; }
        public String fingerprint() { call("fingerprint"); return "hash"; }
        public UUID newToken() { call("token"); return nullToken ? null : token; }
        public String acquire(String hash, UUID token) { call("acquire"); assertEquals("hash", hash); return nullLease ? null : "lease"; }
        public Long completedOrder(String lease) { return completed ? 7L : null; }
        public String replay(Long id) { call("replay"); assertEquals(7L, id); return "replay"; }
        public String prepare() { call("prepare"); return "prepared"; }
        public String transaction(Supplier<String> op) { call("transaction"); String result = op.get(); call("commit"); return result; }
        public String persist(String prepared, String hash, UUID owner) {
            call("persist"); assertEquals("prepared", prepared);
            assertEquals(key ? "hash" : null, hash); assertEquals(key ? token : null, owner);
            return "created";
        }
        public void release(String lease, UUID owner) {
            calls.add("release"); assertEquals("lease", lease); assertEquals(token, owner);
            if (releaseFailure) throw new IllegalStateException("release");
        }
    }
    @Test void leasePrecedesPreflightAndTransactionAndReplaySkipsBoth() {
        Ports p = new Ports(); assertEquals("created", CreateOrderWorkflow.execute(p));
        assertEquals(List.of("admit","fingerprint","token","acquire","prepare","transaction","persist","commit"), p.calls);
        p = new Ports(); p.completed = true; assertEquals("replay", CreateOrderWorkflow.execute(p));
        assertEquals(List.of("admit","fingerprint","token","acquire","replay"), p.calls);
        p = new Ports(); p.key = false; assertEquals("created", CreateOrderWorkflow.execute(p));
        assertEquals(List.of("admit","prepare","transaction","persist","commit"), p.calls);
    }
    @Test void onlyOwnedIncompleteLeaseIsReleasedAndOriginalFailureSurvives() {
        for (String stage : List.of("admit","fingerprint","token","acquire","replay","prepare","transaction","persist","commit")) {
            Ports p = new Ports(); p.fail = stage; p.completed = stage.equals("replay"); p.releaseFailure = true;
            assertSame(p.failure, assertThrows(RuntimeException.class, () -> CreateOrderWorkflow.execute(p)));
            boolean released = List.of("prepare","transaction","persist","commit").contains(stage);
            assertEquals(released, p.calls.contains("release"));
            assertEquals(released ? 1 : 0, p.failure.getSuppressed().length);
        }
        for (int variant = 0; variant < 5; variant++) {
            Ports p = new Ports(); p.fail = "prepare";
            p.key = variant != 0; p.nullLease = variant == 1; p.nullToken = variant == 2;
            assertSame(p.failure, assertThrows(RuntimeException.class, () -> CreateOrderWorkflow.execute(p)));
            assertEquals(variant >= 3, p.calls.contains("release"));
        }
        // Models managed receipt mutation before transaction commit fails: retain original no-release behavior.
        Ports completedBeforeCommitFailure = new Ports() {
            public String persist(String a, String b, UUID c) { completed = true; return super.persist(a,b,c); }
            public String transaction(Supplier<String> op) { op.get(); throw failure; }
        };
        assertSame(completedBeforeCommitFailure.failure, assertThrows(RuntimeException.class,
                () -> CreateOrderWorkflow.execute(completedBeforeCommitFailure)));
        assertFalse(completedBeforeCommitFailure.calls.contains("release"));
    }
}
