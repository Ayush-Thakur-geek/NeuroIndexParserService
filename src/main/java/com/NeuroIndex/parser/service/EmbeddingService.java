package com.NeuroIndex.parser.service;

import java.util.List;

public interface EmbeddingService {

    public List<List<Float>> createEmbeddings(List<String> text);
}
