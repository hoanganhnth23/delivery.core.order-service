package com.delivery.order.application;
import com.delivery.order.application.api.IdempotencyPorts;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IdempotencyWorkflowTest {
    static class Ports implements IdempotencyPorts<String> {
        List<String> calls=new ArrayList<>();
        String first, second, locked;
        boolean completed, owned, live, mismatch;
        int inserted, reclaimed, reads;
        RuntimeException progress=new IllegalStateException("progress");
        public void requireArguments(){calls.add("arguments");}
        public String find(){calls.add("find");return reads++==0?first:second;}
        public String findLocked(){calls.add("lock");return locked;}
        public void assertFingerprint(String receipt){calls.add("fingerprint");if(mismatch)throw new IllegalArgumentException("reused");}
        public boolean completed(String receipt){return completed;}
        public boolean ownedAndLive(String receipt){return owned;}
        public boolean live(String receipt){return live;}
        public int insert(){calls.add("insert");return inserted;}
        public int reclaim(){calls.add("reclaim");return reclaimed;}
        public String requireFound(){calls.add("required");return "receipt";}
        public RuntimeException inProgress(){return progress;}
    }
    @Test void initialAndContendingReadsRespectAllLeaseStates() {
        for(boolean initial:List.of(false,true)) {
            for(int state=0;state<6;state++) {
                Ports p=new Ports();p.first=initial?"receipt":null;p.second="receipt";
                p.completed=state==0;p.owned=state==1;p.live=state==2;p.reclaimed=state==3?1:0;p.mismatch=state==4;
                if(state==4)assertThrows(IllegalArgumentException.class,()->IdempotencyWorkflow.acquire(p));
                else if(state==0||state==1||state==3)assertEquals("receipt",IdempotencyWorkflow.acquire(p));
                else assertSame(p.progress,assertThrows(RuntimeException.class,()->IdempotencyWorkflow.acquire(p)));
                if(p.completed||p.owned||p.mismatch||(initial&&p.live))assertFalse(p.calls.contains("reclaim"));
            }
        }
        Ports p=new Ports();p.inserted=1;assertEquals("receipt",IdempotencyWorkflow.acquire(p));
        assertEquals(List.of("arguments","find","insert","required"),p.calls);
        p=new Ports();Ports absent=p;
        assertSame(p.progress,assertThrows(RuntimeException.class,()->IdempotencyWorkflow.acquire(absent)));
        assertFalse(p.calls.contains("reclaim"));
    }
    @Test void finalClaimLocksBeforeFingerprintAndNeverInsertsOrReclaims() {
        for(int state=0;state<5;state++) {
            Ports p=new Ports();p.locked=state==0?null:"receipt";p.mismatch=state==1;p.completed=state==2;p.owned=state==3;
            if(state==2||state==3)assertEquals("receipt",IdempotencyWorkflow.claim(p));
            else if(state==1)assertThrows(IllegalArgumentException.class,()->IdempotencyWorkflow.claim(p));
            else assertSame(p.progress,assertThrows(RuntimeException.class,()->IdempotencyWorkflow.claim(p)));
            assertEquals("lock",p.calls.get(1));assertFalse(p.calls.contains("insert"));assertFalse(p.calls.contains("reclaim"));
        }
    }
}
