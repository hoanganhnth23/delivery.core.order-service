package com.delivery.order.application;
import com.delivery.order.application.api.QuotePorts;
import com.delivery.order.application.api.QuoteIssuePorts;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QuoteWorkflowTest {
    @Test void issuancePersistsOnlyAfterRemoteSuccessAndRetainsEachFailure() {
        for (String failureStage : Arrays.asList(null, "preview", "persist")) {
            List<String> calls = new ArrayList<>();
            RuntimeException failure = new IllegalStateException("dependency");
            QuoteIssuePorts<String> ports = new QuoteIssuePorts<>() {
                public String preview() {
                    calls.add("preview");
                    if ("preview".equals(failureStage)) throw failure;
                    return "canonical";
                }
                public String persist(String preview) {
                    assertEquals("canonical", preview);
                    calls.add("persist");
                    if ("persist".equals(failureStage)) throw failure;
                    return "issued";
                }
            };
            if (failureStage == null) assertEquals("issued", QuoteWorkflow.issue(ports));
            else assertSame(failure, assertThrows(RuntimeException.class, () -> QuoteWorkflow.issue(ports)));
            assertEquals("preview".equals(failureStage) ? List.of("preview") : List.of("preview", "persist"), calls);
        }
    }
    static class Ports implements QuotePorts<String,String,String> {
        List<String> calls=new ArrayList<>();String fail;boolean changed;
        RuntimeException failure=new IllegalArgumentException("failure"), priceChanged=new IllegalStateException("PRICE_CHANGED");
        void call(String stage){calls.add(stage);if(stage.equals(fail))throw failure;}
        public String find(boolean lock){call(lock?"lock":"read");return "quote";}
        public void validate(String quote){call("validate");}
        public String previewInput(){call("input");return "input";}
        public String reprice(String input){call("remote");return "current";}
        public boolean priceChanged(String quote,String current){call("compare");return changed;}
        public String replacement(String input,String current){call("persistReplacement");return "replacement";}
        public RuntimeException changed(String replacement){call("changed");assertEquals("replacement",replacement);return priceChanged;}
        public void admitConsume(String quote){call("admitConsume");}
        public void consume(String quote){call("consume");}
    }
    @Test void readAndRemoteRepriceNeverLockAndReplacementPrecedesConflict() {
        Ports p=new Ports();assertEquals("current",QuoteWorkflow.validateAndReprice(p));
        assertEquals(List.of("read","validate","input","remote","compare"),p.calls);
        p=new Ports();p.changed=true;Ports changed=p;
        assertSame(p.priceChanged,assertThrows(RuntimeException.class,()->QuoteWorkflow.validateAndReprice(changed)));
        assertEquals(List.of("read","validate","input","remote","compare","persistReplacement","changed"),p.calls);
        p=new Ports();QuoteWorkflow.consume(p);assertEquals(List.of("lock","admitConsume","consume"),p.calls);
    }
    @Test void eachFailureStopsAtItsStage() {
        List<String> stages=List.of("read","validate","input","remote","compare","persistReplacement","changed");
        for(String stage:stages){Ports p=new Ports();p.changed=true;p.fail=stage;
            assertSame(p.failure,assertThrows(RuntimeException.class,()->QuoteWorkflow.validateAndReprice(p)));
            assertEquals(stages.subList(0,stages.indexOf(stage)+1),p.calls);
        }
        stages=List.of("lock","admitConsume","consume");
        for(String stage:stages){Ports p=new Ports();p.fail=stage;
            assertSame(p.failure,assertThrows(RuntimeException.class,()->QuoteWorkflow.consume(p)));
            assertEquals(stages.subList(0,stages.indexOf(stage)+1),p.calls);
        }
    }
}
