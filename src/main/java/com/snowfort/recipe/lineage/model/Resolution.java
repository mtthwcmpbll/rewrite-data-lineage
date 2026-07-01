package com.snowfort.recipe.lineage.model;

/**
 * How completely an {@link ExternalIdentifier}'s route was resolved.
 *
 * <ul>
 *   <li>{@link #EXACT} — a fully known route template.</li>
 *   <li>{@link #PARTIAL} — a known prefix plus a placeholder for dynamically-built segments.</li>
 *   <li>{@link #UNKNOWN} — the route could not be resolved at all.</li>
 * </ul>
 *
 * Per constitution Principle IV, low-confidence results are represented as data rather than dropped.
 */
public enum Resolution {
    EXACT,
    PARTIAL,
    UNKNOWN
}
