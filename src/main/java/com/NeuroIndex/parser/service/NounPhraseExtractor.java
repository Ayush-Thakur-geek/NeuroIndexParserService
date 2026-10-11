package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.NounPhraseExtractionDTO;

import java.util.List;

public interface NounPhraseExtractor {

    public List<String> extractNounPhrase(String text);
}
