package com.delivery.order.application;
import com.delivery.order.application.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class VoucherWorkflowTest {
    static class Ports implements VoucherPorts {
        String mode;List<Long> ids=List.of(),auto=List.of(2L);List<String> calls=new ArrayList<>();
        UUID id=UUID.randomUUID();ReservationIds journal;boolean fail;
        public List<Long> selectedIds(){return ids;}
        public String mode(){return mode;}
        public List<Long> autoSelect(){calls.add("auto");return auto;}
        public UUID newId(){return id;}
        public void reservePromotion(UUID id,List<Long> ids){assertEquals(id,journal.promotion);calls.add("promotion");if(fail)throw new IllegalStateException();}
        public void reserveLegacy(UUID id,Long voucherId){assertEquals(id,journal.voucher);assertEquals(1L,voucherId);calls.add("legacy");if(fail)throw new IllegalStateException();}
    }
    @Test void railsAutoSelectionAndAmbiguousFailureJournal() {
        for(int rail=0;rail<5;rail++)for(boolean fail:List.of(false,true)){
            Ports p=new Ports();p.journal=new ReservationIds();p.fail=fail;
            if(rail==1)p.ids=List.of(1L);
            if(rail==2)p.ids=List.of(1L,2L);
            if(rail==3)p.mode="AUTO";
            if(rail==4){p.mode="AUTO";p.auto=List.of();}
            if(fail&&rail>0&&rail<4)assertThrows(IllegalStateException.class,()->VoucherWorkflow.reserve(p,p.journal));
            else VoucherWorkflow.reserve(p,p.journal);
            assertEquals(rail==1?p.id:null,p.journal.voucher);
            assertEquals(rail==2||rail==3?p.id:null,p.journal.promotion);
            assertEquals(rail>=3,p.calls.contains("auto"));
        }
        Ports p=new Ports();p.mode="MANUAL";p.journal=new ReservationIds();
        assertThrows(IllegalArgumentException.class,()->VoucherWorkflow.reserve(p,p.journal));assertTrue(p.calls.isEmpty());
    }
}
