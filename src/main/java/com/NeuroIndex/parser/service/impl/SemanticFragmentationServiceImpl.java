package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.constants.ParserServiceConstants;
import com.NeuroIndex.entity.domainObjects.SemanticUnit;
import com.NeuroIndex.entity.enums.SemanticContentType;
import com.NeuroIndex.entity.models.*;
import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.repositories.MessageRepo;
import com.NeuroIndex.parser.repositories.SemanticFragmentRepo;
import com.NeuroIndex.parser.service.EmbeddingService;
import com.NeuroIndex.parser.service.KeyWordExtractionService;
import com.NeuroIndex.parser.service.NounPhraseExtractor;
import com.NeuroIndex.parser.service.SemanticFragmentationService;
import jakarta.transaction.Transactional;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

@Service
@Log4j2
public class SemanticFragmentationServiceImpl implements SemanticFragmentationService {

    private final ExecutorService executorService;
    private final EmbeddingService embeddingService;
    private final SemanticFragmentRepo semanticFragmentRepo;
    private final KeyWordExtractionService  keyWordExtractionService;
    private final MessageRepo messageRepo;
    private final NounPhraseExtractor nounPhraseExtractor;

    public static final float MAX_DRIFT = 0.25f;
    private static final Pattern CODE_KEYWORDS = Pattern.compile(
            "\\b(public|private|protected|static|void|class|interface|extends|implements|" +
                    "import|package|return|new|throws|catch|finally|def|elif|lambda|self|" +
                    "SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|JOIN|GROUP BY|ORDER BY|" +
                    "function|const|let|var|=>|console\\.log|" +
                    "#!/bin/|echo \\$|sudo |chmod |grep |awk |sed )\\b"
    );

    private static final Pattern CODE_PUNCTUATION_HEAVY = Pattern.compile(
            "[{}();<>\\[\\]]"
    );

    private static final double SYMBOL_RATIO_THRESHOLD = 0.18; // symbols per character
    private static final int    MIN_CONSECUTIVE_CODE_LINES = 1; // a single strong code line is enough
    private static final double LINE_CODE_SCORE_THRESHOLD = 2.0;

    public SemanticFragmentationServiceImpl(
            ExecutorService executorService,
            EmbeddingService embeddingService,
            SemanticFragmentRepo semanticFragmentRepo,
            KeyWordExtractionService keyWordExtractionService,
            MessageRepo messageRepo,
            NounPhraseExtractor nounPhraseExtractor
    ) {
        this.executorService = executorService;
        this.embeddingService = embeddingService;
        this.semanticFragmentRepo = semanticFragmentRepo;
        this.keyWordExtractionService = keyWordExtractionService;
        this.messageRepo = messageRepo;
        this.nounPhraseExtractor = nounPhraseExtractor;
    }

    @Override
    public void messageSemanticFragmentation(
            Message message,
            List<SemanticUnit> semanticUnits
    ) {
        List<SemanticFragment> semanticFragmentsToSave;
        if (message.getSemanticFragments() != null) {
            semanticFragmentsToSave = message.getSemanticFragments();
        } else {
            semanticFragmentsToSave = new ArrayList<>();
        }
        for (SemanticUnit semanticUnit : semanticUnits) {
            try {
                SemanticContentType type =
                        semanticUnit.getSemanticContentType();
                switch (type) {
                    case TEXT -> {
                        String unfilteredText = semanticUnit.getExtractedText();
                        String filteredText = removeCodeBlocks(unfilteredText);
                        filteredText = normalizeMarkdown(filteredText);
                        semanticUnit.setExtractedText(filteredText);
                        List<SemanticFragment> semanticFragments = textSemanticChunking(message, semanticUnit);
                        if (!semanticFragments.isEmpty()) {
                            semanticFragmentsToSave.addAll(semanticFragments);
                        }
                    }
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
        message.setSemanticFragments(semanticFragmentsToSave);
        messageRepo.save(message);
    }

    /**
     * Removes both explicitly fenced code (```...``` or `...`) and unfenced
     * raw code pasted inline, by scoring each line for "code-likelihood"
     * and stripping consecutive runs of code-like lines.
     */
    private String removeCodeBlocks(String text) {

        // Step 1: strip fenced code blocks entirely (these are standalone,
        // safe to remove without a placeholder — no surrounding sentence
        // depends on a fenced block's content).
        String withoutFencedBlocks = text.replaceAll("(?s)```.*?```", " ");

        // Step 2: replace INLINE code with a neutral placeholder noun
        // instead of deleting it. An inline reference like `for` or
        // `sendMessage()` is often a grammatical object inside a real
        // sentence — deleting it outright leaves broken remnants
        // ("Proper use of  loops"), while dropping the whole line (an
        // earlier approach) loses unrelated real content in the same
        // bullet ("WebSocket endpoints" in this example). A placeholder
        // keeps the sentence parseable so the rest of the line still
        // chunks correctly.
        String withPlaceholders = withoutFencedBlocks.replaceAll("`[^`]*`", " code ");

        // Step 3: strip unfenced raw code line-by-line (unchanged from before)
        String[] lines = withPlaceholders.split("\n", -1);
        StringBuilder cleaned = new StringBuilder();

        int i = 0;
        while (i < lines.length) {

            if (isCodeLikeLine(lines[i])) {
                while (i < lines.length && isCodeLikeLine(lines[i])) {
                    i++;
                }
                cleaned.append(" ");
            } else {
                cleaned.append(lines[i]).append("\n");
                i++;
            }
        }

        return cleaned.toString().replaceAll("[ \\t]{2,}", " ").trim();
    }

    /**
     * Heuristic line classifier. Returns true if the line looks like source
     * code (Java, Python, SQL, shell, JSON, JS) rather than natural-language
     * prose. Tuned for precision over recall — false negatives (code that
     * slips through) are safer than false positives (prose getting dropped).
     */
    private boolean isCodeLikeLine(String line) {

        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;

        double score = 0.0;

        // Signal 1: known code keywords across common languages
        if (CODE_KEYWORDS.matcher(trimmed).find()) {
            score += 2.0;
        }

        // Signal 2: line ends in a code-typical terminator
        if (trimmed.endsWith(";") || trimmed.endsWith("{") || trimmed.endsWith("}")) {
            score += 1.5;
        }

        // Signal 3: symbol density (punctuation per character)
        long symbolCount = trimmed.chars()
                .filter(c -> "{}();<>[]=&|%$#@".indexOf(c) >= 0)
                .count();
        double symbolRatio = (double) symbolCount / trimmed.length();
        if (symbolRatio >= SYMBOL_RATIO_THRESHOLD) {
            score += 1.5;
        }

        // Signal 4: heavy leading indentation (common in code, rare in prose)
        int leadingSpaces = line.length() - line.stripLeading().length();
        if (leadingSpaces >= 4 || line.startsWith("\t")) {
            score += 1.0;
        }

        // Signal 5: looks like a JSON key-value or object literal fragment
        if (trimmed.matches(".*\"[A-Za-z0-9_]+\"\\s*:\\s*.*")) {
            score += 1.5;
        }

        // Signal 6: camelCase or snake_case identifier density (variable/method names)
        long identifierLikeTokens = Arrays.stream(trimmed.split("\\s+"))
                .filter(t -> t.matches("[a-z]+[A-Z][a-zA-Z0-9]*") || t.matches("[a-z0-9_]+_[a-z0-9_]+"))
                .count();
        if (identifierLikeTokens >= 2) {
            score += 1.0;
        }

        return score >= LINE_CODE_SCORE_THRESHOLD;
    }

    private List<SemanticFragment> textSemanticChunking(
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

            return new ArrayList<>();
        }

        List<SemanticFragment> semanticFragments = new ArrayList<>();

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

                SemanticFragment semanticFragment = saveFragment(
                        currentChunk.toString(),
                        message,
                        currentCentroid,
                        ++chunkCount,
                        similarity
                );
                semanticFragments.add(semanticFragment);

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

        SemanticFragment semanticFragment = saveFragment(
                currentChunk.toString(),
                message,
                currentCentroid,
                ++chunkCount,
                lastSimilarity
        );

        semanticFragments.add(semanticFragment);
        return semanticFragments;
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
    protected SemanticFragment saveFragment(
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


        //Subject for removal after implementation of jwt
        String hash = hashChunk(message, chunkCount, text);
        Conversation conversation = message.getConversation();
        AffiliatedEmail affiliatedEmail = conversation.getAffiliatedEmail();
        LLm llm = affiliatedEmail.getLlm();
        User user = llm.getUser();
        //-------------------------------------------------------

        List<String> nounPhrases = nounPhraseExtractor.extractNounPhrase(text);

        log.info("Noun phrases -> {}", nounPhrases);

        SemanticFragment semanticFragment = SemanticFragment.builder()
                .message(message)
                .text(text)
                .embedding(embedding)
                .hash(hash)
                .fragmentOrder(chunkCount)
                .confidenceScore(1f - similarity)
                .nounPhrases(nounPhrases)
                .build();
        semanticFragmentRepo.save(semanticFragment);

        LuceneIndexDataDTO luceneIndexDataDTO = LuceneIndexDataDTO.builder()
                .userId(user.getId())
                .llmId(llm.getId())
                .affiliatedEmailId(affiliatedEmail.getId())
                .conversationId(conversation.getId())
                .messageId(message.getId())
                .semanticFragmentId(semanticFragment.getId())
                .text(semanticFragment.getText())
                .nounPhrases(nounPhrases)
                .build();

        keyWordExtractionService.indexing(luceneIndexDataDTO);
        return semanticFragment;
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

    private String normalizeMarkdown(String text) {

        String result = text
                // Remove bold/italic markers
                .replaceAll("\\*+", "")

                // Remove markdown headings
                .replaceAll("(?m)^#+\\s*", "")

                // Convert bullets into sentences
                .replaceAll("(?m)^\\s*[-*+]\\s*", "")

                // Convert numbered lists
                .replaceAll("(?m)^\\s*\\d+\\.\\s*", "");

        // Join lines into sentences, but avoid stacking a period after a
        // line that already ends in terminal punctuation (., !, ?, :) —
        // unconditionally appending ". " after every newline produces
        // artifacts like "Working:. Proper use..." which can confuse
        // sentence/chunk boundary detection downstream.
        String[] lines = result.split("\n");
        StringBuilder joined = new StringBuilder();

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) continue;

            if (joined.length() > 0) {
                char lastChar = joined.charAt(joined.length() - 1);
                if (lastChar != '.' && lastChar != '!' && lastChar != '?' && lastChar != ':') {
                    joined.append(". ");
                } else {
                    joined.append(" ");
                }
            }

            joined.append(trimmedLine);
        }

        return joined.toString()
                .replaceAll("\\s+", " ")
                .trim();
    }
}
