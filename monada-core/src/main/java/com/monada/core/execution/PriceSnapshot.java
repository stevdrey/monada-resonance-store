package com.monada.core.execution;

import java.time.LocalDate;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Caller-supplied, versioned price snapshot used only for hypothetical cost estimates. The library
 * never looks up prices. A snapshot has at least one line and at most one line per counter kind.
 */
public record PriceSnapshot(
        String id,
        String version,
        String provider,
        String model,
        Currency currency,
        LocalDate effectiveDate,
        String source,
        String assumptions,
        List<PriceLine> lines
) {
    public PriceSnapshot {
        id = Validation.opaque(id, "snapshot id");
        version = Validation.opaque(version, "snapshot version");
        provider = Validation.opaque(provider, "provider");
        model = Validation.opaque(model, "model");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        source = Validation.opaque(source, "source");
        assumptions = Validation.summary(assumptions, "assumptions");
        lines = Validation.boundedList(lines, "lines", UsageKind.values().length);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a price snapshot requires at least one price line");
        }
        Validation.requireUnique(lines, PriceLine::kind, "price line kind");
        Set<UsageKind> priced = EnumSet.noneOf(UsageKind.class);
        lines.forEach(line -> priced.add(line.kind()));
        for (PriceLine line : lines) {
            if (line.includedIn().isPresent() && !priced.contains(line.includedIn().get())) {
                throw new IllegalArgumentException(line.kind() + " is included in unpriced kind "
                        + line.includedIn().get());
            }
        }
    }

    /** Resolves an ISO 4217 code; rejects unknown codes. */
    public static Currency currency(String isoCode) {
        Objects.requireNonNull(isoCode, "isoCode");
        try {
            return Currency.getInstance(isoCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not an ISO 4217 currency code: " + isoCode, e);
        }
    }
}
