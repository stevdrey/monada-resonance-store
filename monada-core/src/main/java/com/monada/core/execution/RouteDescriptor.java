package com.monada.core.execution;

import java.util.Objects;
import java.util.Optional;

/** Descriptive worker/provider/model/effort route of a stage. Absent values are explicitly unavailable. */
public record RouteDescriptor(
        String worker,
        Optional<String> provider,
        Optional<String> model,
        Optional<String> effort,
        BillingMode billingMode
) {
    public RouteDescriptor {
        worker = Validation.opaque(worker, "worker");
        provider = optionalOpaque(provider, "provider");
        model = optionalOpaque(model, "model");
        effort = optionalOpaque(effort, "effort");
        Objects.requireNonNull(billingMode, "billingMode");
    }

    /** Route that states only the worker; everything else is unavailable and billing is UNKNOWN. */
    public static RouteDescriptor workerOnly(String worker) {
        return new RouteDescriptor(worker, Optional.empty(), Optional.empty(), Optional.empty(), BillingMode.UNKNOWN);
    }

    private static Optional<String> optionalOpaque(Optional<String> value, String field) {
        Objects.requireNonNull(value, field);
        return value.map(v -> Validation.opaque(v, field));
    }
}
