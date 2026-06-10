package com.monada.speech.bridge;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.speech.domain.SpeechSample;

import java.util.Map;

/**
 * Maps a SpeechSample to a KnowledgeAtom representing its transcript.
 * <p>
 * This mapper creates a TEXT atom from the speech sample's transcript,
 * preserving the sample id, aliases, and creation timestamp. The atom
 * contains empty metadata to keep audio-specific details separate from
 * the text resonance memory.
 * <p>
 * This allows speech datasets (like TORGO) to populate normal text
 * resonance memory with transcripts while keeping speech-specific metadata
 * and acoustic vectors in the monada-speech module.
 */
public final class SpeechSampleAtomMapper {

    /**
     * Converts a SpeechSample to a KnowledgeAtom with AtomType.TEXT.
     *
     * @param sample the speech sample to convert
     * @return a KnowledgeAtom containing the transcript, empty metadata
     */
    public KnowledgeAtom toTranscriptAtom(SpeechSample sample) {
        return new KnowledgeAtom(
                sample.id(),
                AtomType.TEXT,
                sample.transcript(),
                sample.aliases(),
                Map.of(),
                1.0,
                sample.createdAt()
        );
    }
}
