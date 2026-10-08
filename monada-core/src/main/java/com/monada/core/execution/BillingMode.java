package com.monada.core.execution;

/** Billing mode of a route. Subscription usage is never API billing. */
public enum BillingMode {
    API_METERED, SUBSCRIPTION, LOCAL, UNKNOWN
}
