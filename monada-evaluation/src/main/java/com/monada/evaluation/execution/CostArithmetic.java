package com.monada.evaluation.execution;

import com.monada.core.execution.PriceLine;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact decimal cost arithmetic with the documented rounding (contract section 9 and 19). */
final class CostArithmetic {
    static final int DIVISION_SCALE = 18;
    static final int REPORT_SCALE = 6;
    static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private CostArithmetic() {
    }

    /** {@code billable * price / unitSize}: exact product, one division at scale 18. */
    static BigDecimal lineCost(long billable, PriceLine line) {
        return BigDecimal.valueOf(billable).multiply(line.price())
                .divide(BigDecimal.valueOf(line.unitSize()), DIVISION_SCALE, ROUNDING);
    }

    /** The reported amount: a single rounding of the exact total. */
    static BigDecimal report(BigDecimal unrounded) {
        return unrounded.setScale(REPORT_SCALE, ROUNDING);
    }
}
