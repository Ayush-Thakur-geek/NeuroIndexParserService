package com.NeuroIndex.parser.service;

import com.NeuroIndex.entity.domainObjects.SemanticUnit;
import com.NeuroIndex.entity.models.Message;

import java.util.List;

public interface SemanticFragmentationService {
    public void messageSemanticFragmentation(Message message, List<SemanticUnit> semanticUnits);
}
