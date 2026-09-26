package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.PhraseToNodeDTO;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface GraphFormationService {
    public void initialPreparations(Long userId, Long semanticFragmentId, Set<PhraseToNodeDTO> phraseDto, Set<String> keywords);
    public void initiateGraphFormation(Long userId);
}
