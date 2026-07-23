package com.monada.speech.importer;

/**
 * Classifies the reason a WAV file was skipped during a TORGO corpus import
 * or audit scan.
 *
 * <p>Each constant carries a human-readable {@link #displayName()} used when
 * the lightweight {@link TorgoDatasetImportWarning} reason string is derived
 * from this category.
 */
public enum WarningCategory {

    /** No supported sibling or native TORGO prompt transcript file was found. */
    MISSING_TRANSCRIPT("missing transcript"),

    /** A selected sibling or native TORGO prompt transcript is blank after trimming. */
    BLANK_TRANSCRIPT("blank transcript"),

    /** The selected sibling or native TORGO prompt transcript could not be read (I/O error). */
    UNREADABLE_TRANSCRIPT("unreadable transcript"),

    /** The WAV file exists but could not be parsed or has an unsupported format. */
    UNREADABLE_AUDIO("unreadable audio"),

    /**
     * The file's path does not conform to the expected layout convention (e.g.
     * a WAV placed directly under the dataset root with no speaker directory).
     */
    UNSUPPORTED_LAYOUT("unsupported layout"),

    /**
     * Two or more WAV files in a single run derive to the same stable sample ID
     * (e.g. names that differ only by case).
     */
    DUPLICATE_ID("duplicate sample id");

    private final String displayName;

    WarningCategory(String displayName) {
        this.displayName = displayName;
    }

    /** Returns a human-readable description of this warning category. */
    public String displayName() {
        return displayName;
    }
}
