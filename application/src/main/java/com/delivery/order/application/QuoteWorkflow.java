package com.delivery.order.application;
import com.delivery.order.application.api.QuotePorts;
import com.delivery.order.application.api.QuoteIssuePorts;
public final class QuoteWorkflow {
    private QuoteWorkflow() {}
    public static <R> R issue(QuoteIssuePorts<R> ports) {
        return ports.persist(ports.preview());
    }
    public static <Q, P, R> R validateAndReprice(QuotePorts<Q, P, R> ports) {
        Q quote = ports.find(false);
        ports.validate(quote);
        P input = ports.previewInput();
        R current = ports.reprice(input);
        if (ports.priceChanged(quote, current)) throw ports.changed(ports.replacement(input, current));
        return current;
    }
    public static <Q, P, R> void consume(QuotePorts<Q, P, R> ports) {
        Q quote = ports.find(true);
        ports.admitConsume(quote);
        ports.consume(quote);
    }
}
