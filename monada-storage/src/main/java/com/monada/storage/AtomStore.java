package com.monada.storage;

import com.monada.core.KnowledgeAtom;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface AtomStore {
    void save(KnowledgeAtom atom) throws IOException;

    Optional<KnowledgeAtom> findById(String id) throws IOException;

    List<KnowledgeAtom> findAll() throws IOException;
}
