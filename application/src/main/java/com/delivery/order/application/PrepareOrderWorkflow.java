package com.delivery.order.application;
import com.delivery.order.application.api.PrepareOrderPorts;
public final class PrepareOrderWorkflow {
    private PrepareOrderWorkflow() {}
    public static <Q, V, P> P execute(PrepareOrderPorts<Q, V, P> ports) {
        ports.admitSelections();
        Q quote = ports.hasQuote() ? ports.validateQuote() : null;
        V facts = ports.canonicalFacts();
        ports.requireCanonical(facts);
        return ports.prepared(facts, quote);
    }
}
