package com.delivery.order.application;
import com.delivery.order.application.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReservationWorkflowTest {
    static class Ports implements ReservationPorts<String> {
        List<String> calls = new ArrayList<>();
        Map<String, UUID> attempted = new LinkedHashMap<>(), released = new LinkedHashMap<>();
        boolean inventory = true, flash = true, quote = true, receipt = true, vouchers = true, releaseFailure;
        String fail;
        RuntimeException failure = new IllegalArgumentException("original");
        void call(String stage) { calls.add(stage); if (stage.equals(fail)) throw failure; }
        public boolean inventoryEnabled() { return inventory; }
        public boolean hasFlash() { return flash; }
        public UUID newId() { return UUID.randomUUID(); }
        public void reserveInventory(UUID id) { attempted.put("inventory",id); call("inventory"); }
        public void reserveFlash(UUID id) { attempted.put("flash",id); call("flash"); }
        public void priceAndReserveVouchers(ReservationIds ids) {
            call("price");
            if (vouchers) {
                ids.voucher = UUID.randomUUID(); attempted.put("voucher",ids.voucher); call("voucher");
                ids.promotion = UUID.randomUUID(); attempted.put("promotion",ids.promotion); call("promotion");
            }
        }
        public void snapshot() { call("snapshot"); }
        public void commitInventory(UUID id) { assertEquals(attempted.get("inventory"),id); call("commitInventory"); }
        public boolean hasQuote() { return quote; }
        public void consumeQuote() { call("consumeQuote"); }
        public boolean hasReceipt() { return receipt; }
        public void completeReceipt() { call("completeReceipt"); }
        public void publishCreated() { call("publish"); }
        public String response() { call("response"); return "created"; }
        void release(String rail, UUID id) {
            released.put(rail,id); calls.add("release:"+rail);
            if (releaseFailure) throw new IllegalStateException(rail);
        }
        public void releaseVoucher(UUID id) { release("voucher",id); }
        public void releasePromotion(UUID id) { release("promotion",id); }
        public void releaseFlash(UUID id) { release("flash",id); }
        public void releaseInventory(UUID id) { release("inventory",id); }
    }
    @Test void sequenceAndAllOptionalCombinations() {
        for (int mask=0; mask<32; mask++) {
            Ports p = new Ports(); p.inventory=(mask&1)!=0; p.flash=(mask&2)!=0;
            p.quote=(mask&4)!=0; p.receipt=(mask&8)!=0; p.vouchers=(mask&16)!=0;
            assertEquals("created", ReservationWorkflow.execute(p));
            List<String> expected = new ArrayList<>();
            if(p.inventory) expected.add("inventory"); if(p.flash) expected.add("flash"); expected.add("price");
            if(p.vouchers) { expected.add("voucher"); expected.add("promotion"); }
            expected.add("snapshot"); if(p.inventory) expected.add("commitInventory");
            if(p.quote) expected.add("consumeQuote"); if(p.receipt) expected.add("completeReceipt");
            expected.add("publish"); expected.add("response");
            assertEquals(expected,p.calls); assertTrue(p.released.isEmpty());
        }
    }
    @Test void everyFailureIncludingAmbiguousReserveReleasesIdenticalIdsInLegacyOrder() {
        for (boolean releaseFailure : List.of(false,true)) {
            for (String stage : List.of("inventory","flash","price","voucher","promotion","snapshot","commitInventory","consumeQuote","completeReceipt","publish","response")) {
                Ports p = new Ports(); p.fail=stage; p.releaseFailure=releaseFailure;
                assertSame(p.failure, assertThrows(RuntimeException.class, () -> ReservationWorkflow.execute(p)));
                assertEquals(p.attempted,p.released);
                List<String> expected = List.of("voucher","promotion","flash","inventory").stream()
                        .filter(p.attempted::containsKey).toList();
                assertEquals(expected,new ArrayList<>(p.released.keySet()));
                assertEquals(releaseFailure ? expected.size() : 0,p.failure.getSuppressed().length);
                for(int i=0;i<p.failure.getSuppressed().length;i++)
                    assertEquals(expected.get(i),p.failure.getSuppressed()[i].getMessage());
            }
        }
        Ports p = new Ports(); p.inventory=false; p.flash=false; p.vouchers=false; p.fail="snapshot";
        assertSame(p.failure,assertThrows(RuntimeException.class,()->ReservationWorkflow.execute(p)));
        assertTrue(p.released.isEmpty());
    }
}
