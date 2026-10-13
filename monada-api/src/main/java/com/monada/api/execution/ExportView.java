package com.monada.api.execution;

/**
 * How much a sample export exposes (contract v1, section 12).
 *
 * <ul>
 *   <li>{@code ADAPTER_READY}: the default for a consumer. Only observations recorded under the execution's own
 *   evaluation policy are listed and no diagnostic detail is attached. Labels are identical in both views: a
 *   sample is never labelled {@code SUCCESS} while a mandatory gate is {@code UNKNOWN}, {@code FAIL} or missing.</li>
 *   <li>{@code DIAGNOSTIC}: the same samples plus the reasons behind the derived acceptance, the full outcome
 *   history and every observation that was ignored for acceptance (other policy, other attempt).</li>
 * </ul>
 */
public enum ExportView { ADAPTER_READY, DIAGNOSTIC }
