package com.delivery.order.application;
import com.delivery.order.application.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CreateStagesTest {
    static class Prepare implements PrepareOrderPorts<String,String,String> {
        List<String> calls = new ArrayList<>();boolean quote=true;String fail;
        RuntimeException failure=new IllegalArgumentException();
        void call(String step){calls.add(step);if(step.equals(fail))throw failure;}
        public void admitSelections(){call("admit");}
        public boolean hasQuote(){return quote;}
        public String validateQuote(){call("quote");return "quote";}
        public String canonicalFacts(){call("canonical");return "facts";}
        public void requireCanonical(String facts){call("require");assertEquals("facts",facts);}
        public String prepared(String facts,String confirmed){call("prepared");assertEquals(quote?"quote":null,confirmed);return "prepared";}
    }
    static class Write implements CreateWritePorts<String,String,String> {
        List<String> calls=new ArrayList<>();boolean key=true,completed;String fail;
        RuntimeException failure=new IllegalArgumentException();
        void call(String step){calls.add(step);if(step.equals(fail))throw failure;}
        public boolean hasKey(){return key;}
        public String claim(){call("claim");return "receipt";}
        public Long completedOrder(String receipt){assertEquals("receipt",receipt);return completed?7L:null;}
        public String replay(Long id){call("replay");assertEquals(7L,id);return "replay";}
        public String flushShell(){call("shell");return "shell";}
        public String reserveAndSnapshot(String shell,String receipt){call("reserve");assertEquals("shell",shell);assertEquals(key?"receipt":null,receipt);return "created";}
    }
    @Test void preflightOrderAndFailurePrecedence() {
        for(boolean quote:List.of(false,true)){
            Prepare p=new Prepare();p.quote=quote;assertEquals("prepared",PrepareOrderWorkflow.execute(p));
            List<String> stages=quote?List.of("admit","quote","canonical","require","prepared"):List.of("admit","canonical","require","prepared");
            assertEquals(stages,p.calls);
            for(String stage:stages){Prepare failed=new Prepare();failed.quote=quote;failed.fail=stage;
                assertSame(failed.failure,assertThrows(RuntimeException.class,()->PrepareOrderWorkflow.execute(failed)));
                assertEquals(stages.subList(0,stages.indexOf(stage)+1),failed.calls);
            }
        }
    }
    @Test void finalReplayPrecedesShellAndEveryWriteFailureStops() {
        for(int variant=0;variant<3;variant++){
            Write p=new Write();p.key=variant!=0;p.completed=variant==2;
            assertEquals(p.completed?"replay":"created",CreateWriteWorkflow.execute(p));
            List<String> stages=p.completed?List.of("claim","replay"):p.key?List.of("claim","shell","reserve"):List.of("shell","reserve");
            assertEquals(stages,p.calls);
            for(String stage:stages){Write failed=new Write();failed.key=p.key;failed.completed=p.completed;failed.fail=stage;
                assertSame(failed.failure,assertThrows(RuntimeException.class,()->CreateWriteWorkflow.execute(failed)));
                assertEquals(stages.subList(0,stages.indexOf(stage)+1),failed.calls);
            }
        }
    }
    static class Legacy implements LegacyIdempotencyPorts<String> {
        String found,required="receipt";int inserted;boolean completed;
        List<String> calls=new ArrayList<>();
        public String find(){calls.add("find");return found;}
        public int insert(){calls.add("insert");return inserted;}
        public String requireFound(){calls.add("found");return required;}
        public String requireExisting(){calls.add("existing");return required;}
        public void assertFingerprint(String receipt){calls.add("fingerprint");}
        public boolean completed(String receipt){return completed;}
        public RuntimeException inProgress(){return new IllegalStateException("progress");}
    }
    @Test void legacyAtomicInsertAndConcurrentReceiptKeepOriginalFence() {
        for(boolean exists:List.of(false,true))for(boolean completed:List.of(false,true))for(int inserted:new int[]{0,1}){
            Legacy p=new Legacy();p.found=exists?"receipt":null;p.completed=completed;p.inserted=inserted;
            if(completed||(!exists&&inserted==1))assertEquals("receipt",IdempotencyWorkflow.legacyClaim(p));
            else assertThrows(IllegalStateException.class,()->IdempotencyWorkflow.legacyClaim(p));
            assertEquals(!exists,p.calls.contains("insert"));
        }
        Legacy p=new Legacy();p.inserted=1;p.required=null;p.completed=true;
        assertNull(IdempotencyWorkflow.legacyClaim(p));assertTrue(p.calls.contains("existing"));
    }
}
