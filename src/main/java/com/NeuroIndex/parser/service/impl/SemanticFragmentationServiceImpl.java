package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.constants.ParserServiceConstants;
import com.NeuroIndex.entity.domainObjects.SemanticUnit;
import com.NeuroIndex.entity.enums.SemanticContentType;
import com.NeuroIndex.entity.models.Message;
import com.NeuroIndex.entity.models.SemanticFragment;
import com.NeuroIndex.parser.repositories.SemanticFragmentRepo;
import com.NeuroIndex.parser.service.EmbeddingService;
import com.NeuroIndex.parser.service.KeyWordExtractionService;
import com.NeuroIndex.parser.service.SemanticFragmentationService;
import jakarta.transaction.Transactional;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Service
@Log4j2
public class SemanticFragmentationServiceImpl implements SemanticFragmentationService {

    private final ExecutorService executorService;
    private final EmbeddingService embeddingService;
    private final SemanticFragmentRepo semanticFragmentRepo;
    private final KeyWordExtractionService  keyWordExtractionService;

    public static final float MAX_DRIFT = 0.25f;
    private static final int MAX_CHARS = 1800;

    public SemanticFragmentationServiceImpl(
            ExecutorService executorService,
            EmbeddingService embeddingService,
            SemanticFragmentRepo semanticFragmentRepo,
            KeyWordExtractionService keyWordExtractionService
            ) {
        this.executorService = executorService;
        this.embeddingService = embeddingService;
        this.semanticFragmentRepo = semanticFragmentRepo;
        this.keyWordExtractionService = keyWordExtractionService;
    }

    @Override
    public void messageSemanticFragmentation(
            Message message,
            List<SemanticUnit> semanticUnits
    ) {
        for (SemanticUnit semanticUnit : semanticUnits) {
            try {
                SemanticContentType type =
                        semanticUnit.getSemanticContentType();
                switch (type) {
                    case TEXT -> textSemanticChunking(message, semanticUnit);
                    case CODE -> codeSemanticChunking(message, semanticUnit);
                }
            } catch (Exception e) {
                // log and continue — don't kill the ingestion
                log.error(
                        "Fragmentation failed for message [{}], skipping unit. Reason: {}",
                        message.getId(),
                        e.getMessage()
                );
            }
        }
    }

    private void textSemanticChunking(
            Message message,
            SemanticUnit semanticUnit
    ) {

        String text =
                semanticUnit.getExtractedText();

        String[] units = Arrays.stream(
                        text.split("\\r?\\n\\r?\\n")
                )
                .filter(u -> u != null && !u.isBlank())
                .filter(u -> !isSemanticNoise(u))
                .toArray(String[]::new);

        if (units.length == 0) {

            log.info("No units found");

            return;
        }

        List<String> paragraphs =
                Arrays.asList(units);

        List<List<Float>> paragraphEmbeddings =
                embeddingService.createEmbeddings(
                        paragraphs
                );

        List<float[]> embeddings =
                paragraphEmbeddings.stream()
                        .map(this::toPrimitive)
                        .toList();

        StringBuilder currentChunk =
                new StringBuilder(units[0]);

        float[] currentCentroid =
                embeddings.getFirst();

        float[] snapshotCentroid =
                currentCentroid.clone();

        int chunkSize = 1;

        int chunkCount = 0;

        float lastSimilarity = 1f;

        for (int i = 1; i < units.length; i++) {

            float[] nextEmbedding =
                    embeddings.get(i);

            float similarity =
                    cosine(
                            currentCentroid,
                            nextEmbedding
                    );

            float drift =
                    1f - cosine(
                            snapshotCentroid,
                            nextEmbedding
                    );

            if (
                    similarity >
                            ParserServiceConstants.THRESHOLD
                            &&
                            drift < MAX_DRIFT
            ) {

                currentChunk
                        .append("\n")
                        .append(units[i]);

                currentCentroid =
                        updateCentroid(
                                currentCentroid,
                                nextEmbedding,
                                chunkSize
                        );

                chunkSize++;

            } else {

                saveFragment(
                        currentChunk.toString(),
                        message,
                        currentCentroid,
                        ++chunkCount,
                        similarity
                );

                currentChunk =
                        new StringBuilder(units[i]);

                currentCentroid =
                        nextEmbedding;

                snapshotCentroid =
                        nextEmbedding.clone();

                chunkSize = 1;
            }

            lastSimilarity = similarity;
        }

        saveFragment(
                currentChunk.toString(),
                message,
                currentCentroid,
                ++chunkCount,
                lastSimilarity
        );
    }



    private void codeSemanticChunking(Message message, SemanticUnit semanticUnit) {

    }

    private float[] toPrimitive(
            List<Float> embedding
    ) {

        float[] result =
                new float[embedding.size()];

        for (int i = 0; i < embedding.size(); i++) {

            result[i] =
                    embedding.get(i);
        }

        return result;
    }

    private boolean isSemanticNoise(String text) {

        if (text == null || text.isBlank()) {
            return true;
        }

        String normalized =
                normalizeContent(text).trim();

        // remove all non-alphanumeric chars
        String semantic =
                normalized.replaceAll(
                        "[^a-zA-Z0-9]",
                        ""
                );

        boolean noise =
                semantic.length() < 3;

        log.info(
                "Noise check => [{}] => {}",
                normalized,
                noise
        );

        return noise;
    }

    private float[] updateCentroid(float[] current, float[] next, int n) {
        float[] updated = new float[current.length];
        for (int i = 0; i < current.length; i++) {
            updated[i] = (current[i] * n + next[i]) / (n + 1);
        }
        return updated;
    }

    private float cosine(
            float[] currentEmbedding,
            float[] nextEmbedding
    ) {

        float dot = 0f;
        float norm1 = 0f;
        float norm2 = 0f;

        for (int i = 0; i < currentEmbedding.length; i++) {

            dot +=
                    currentEmbedding[i] *
                            nextEmbedding[i];

            norm1 +=
                    currentEmbedding[i] *
                            currentEmbedding[i];

            norm2 +=
                    nextEmbedding[i] *
                            nextEmbedding[i];
        }

        float denominator =
                (float)(
                        Math.sqrt(norm1) *
                                Math.sqrt(norm2)
                );

        if (denominator == 0f) {
            return 0f;
        }

        float similarity =
                dot / denominator;

        if (
                Float.isNaN(similarity) ||
                        Float.isInfinite(similarity)
        ) {
            return 0f;
        }

        return similarity;
    }

    @Transactional
    protected void saveFragment(
            String text,
            Message message,
            float[] embedding,
            int chunkCount,
            float similarity
    ) {
        if (message.getId() == null) {
            throw new RuntimeException(
                    "Message ID is null during fragment save"
            );
        }
        String hash = hashChunk(message, chunkCount, text);

        SemanticFragment semanticFragment = SemanticFragment.builder()
                .message(message)
                .text(text)
                .embedding(embedding)
                .hash(hash)
                .fragmentOrder(chunkCount)
                .confidenceScore(1f - similarity)
                .build();
        semanticFragmentRepo.save(semanticFragment);
    }

    private String hashChunk(
            Message message,
            int chunkCount,
            String text
    ) {
        log.info(
                "HASH INPUT => {} :: {} :: {}",
                message.getId(),
                chunkCount,
                normalizeContent(text)
        );
        return sha256(
                message.getId()
                        + "::"
                        + chunkCount
                        + "::"
                        + normalizeContent(text)
        );
    }

    private String normalizeContent(
            String text
    ) {

        if (text == null) {
            return "";
        }

        return text
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String sha256(
            String input
    ) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            input.getBytes(StandardCharsets.UTF_8)
                    );

            StringBuilder builder =
                    new StringBuilder();

            for (byte b : hash) {

                builder.append(
                        String.format("%02x", b)
                );
            }

            return builder.toString();

        } catch (Exception e) {

            throw new RuntimeException(e);
        }
    }
}
