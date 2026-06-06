package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.domainObjects.SemanticUnit;
import com.NeuroIndex.entity.enums.SemanticContentType;
import com.NeuroIndex.entity.models.*;
import com.NeuroIndex.parser.dtos.ClaudeConversationJsonDTO;
import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.repositories.AffiliatedEmailsRepo;
import com.NeuroIndex.parser.repositories.ConversationRepo;
import com.NeuroIndex.parser.repositories.MessageRepo;
import com.NeuroIndex.parser.service.ClaudeIngestionService;
import com.NeuroIndex.parser.service.KeyWordExtractionService;
import com.NeuroIndex.parser.service.SemanticFragmentationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
@Log4j2
public class ClaudeIngestionServiceImpl implements ClaudeIngestionService {

    private final ConversationRepo conversationRepo;
    private final MessageRepo messageRepo;
    private final AffiliatedEmailsRepo affiliatedEmailRepo;
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;
    private final SemanticFragmentationService semanticFragmentationService;
    private final ApplicationEventPublisher eventPublisher;
    private final KeyWordExtractionService keyWordExtractionService;

    ClaudeIngestionServiceImpl(ConversationRepo conversationRepo,
                               MessageRepo messageRepo,
                               AffiliatedEmailsRepo affiliatedEmailRepo,
                               ExecutorService executorService,
                               ObjectMapper objectMapper,
                               SemanticFragmentationService semanticFragmentationService,
                               KeyWordExtractionService keyWordExtractionService,
                               ApplicationEventPublisher eventPublisher) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.affiliatedEmailRepo = affiliatedEmailRepo;
        this.executorService = executorService;
        this.objectMapper = objectMapper;
        this.semanticFragmentationService = semanticFragmentationService;
        this.keyWordExtractionService = keyWordExtractionService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void ingestClaudeConversations(
            AffiliatedEmail affiliatedEmail,
            List<ClaudeConversationJsonDTO> conversationsDTO
    ) throws JsonProcessingException {

        Set<String> existingConversationHashes =
                conversationRepo.findAllHashes();

        Set<String> existingMessageHashes =
                messageRepo.findAllHashes();

        List<Conversation> conversationsToSave =
                new ArrayList<>();

        // map to hold messages and their semantic units
        // so we can fragment after persist
        Map<Message, List<SemanticUnit>> fragmentationQueue =
                new LinkedHashMap<>();

        for (ClaudeConversationJsonDTO dto : conversationsDTO) {

            String conversationHash =
                    hashConversation(dto);

            if (existingConversationHashes.contains(conversationHash)) {
                continue;
            }

            Conversation conversation =
                    buildConversation(
                            affiliatedEmail,
                            dto,
                            conversationHash
                    );

            List<Message> messages =
                    buildMessages(
                            dto,
                            conversation,
                            existingMessageHashes,
                            fragmentationQueue
                    );

            conversation.setMessages(messages);

            conversationsToSave.add(conversation);

            existingConversationHashes.add(conversationHash);
        }

        // persist first — messages get their IDs here
        conversationRepo.saveAllAndFlush(conversationsToSave);

        // fragment after persist — message IDs now exist in db
        fragmentationQueue.forEach(
                semanticFragmentationService::messageSemanticFragmentation
        );

        LLm llm = affiliatedEmail.getLlm();
        User user = llm.getUser();

        List<LuceneIndexDataDTO> extractionTasks =
                new ArrayList<>();

        for (Conversation conversation : conversationsToSave) {

            List<Message> messages =
                    conversation.getMessages();

            for (Message message : messages) {

                List<SemanticFragment> semanticFragments =
                        message.getSemanticFragments();

                for (SemanticFragment semanticFragment :
                        semanticFragments) {

                    extractionTasks.add(
                            LuceneIndexDataDTO.builder()
                                    .userId(user.getId())
                                    .llmId(llm.getId())
                                    .conversationId(conversation.getId())
                                    .messageId(message.getId())
                                    .semanticFragmentId(
                                            semanticFragment.getId()
                                    )
                                    .text(semanticFragment.getText())
                                    .build()
                    );
                }
            }
        }

        eventPublisher.publishEvent(
                new KeywordExtractionEvent(extractionTasks)
        );

    }

    public record KeywordExtractionEvent(
            List<LuceneIndexDataDTO> tasks
    ) {
    }

    private Conversation buildConversation(
            AffiliatedEmail affiliatedEmail,
            ClaudeConversationJsonDTO dto,
            String hash
    ) {

        String uuid = dto.getUuid();

        String url =
                "https://claude.ai/chat/" + uuid;

        return Conversation.builder()
                .affiliatedEmail(affiliatedEmail)
                .title(dto.getName())
                .url(url)
                .hash(hash)
                .build();
    }

    private List<Message> buildMessages(
            ClaudeConversationJsonDTO dto,
            Conversation conversation,
            Set<String> existingMessageHashes,
            Map<Message, List<SemanticUnit>> fragmentationQueue
    ) throws JsonProcessingException {

        List<Message> messages = new ArrayList<>();

        List<ClaudeConversationJsonDTO.ChatMessage> chatMessages =
                dto.getChatMessages();

        if (chatMessages == null || chatMessages.isEmpty()) {
            return messages;
        }

        int sequence = 0;

        for (ClaudeConversationJsonDTO.ChatMessage chatMessage : chatMessages) {

            List<ClaudeConversationJsonDTO.Content> contents =
                    chatMessage.getContent();

            if (contents == null || contents.isEmpty()) {
                continue;
            }

            List<SemanticUnit> semanticUnits =
                    extractSemanticUnit(contents);

            if (semanticUnits.isEmpty()) {
                continue;
            }

            String rawJson =
                    objectMapper.writeValueAsString(contents);

            String normalizedText =
                    semanticUnits.stream()
                            .map(SemanticUnit::getExtractedText)
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining("\n\n"));

            normalizedText = normalizeContent(normalizedText);

            String messageHash =
                    hashMessage(
                            chatMessage.getSender(),
                            normalizedText
                    );

            if (existingMessageHashes.contains(messageHash)) {
                continue;
            }

            existingMessageHashes.add(messageHash);

            Message message = Message.builder()
                    .conversation(conversation)
                    .sequence_number(sequence++)
                    .role(chatMessage.getSender())
                    .content(semanticUnits)
                    .rawJsonContent(rawJson)
                    .hash(messageHash)
                    .build();

            // queue for fragmentation, not execute yet
            fragmentationQueue.put(message, semanticUnits);

            messages.add(message);
        }

        return messages;
    }

    private List<SemanticUnit> extractSemanticUnit(
            List<ClaudeConversationJsonDTO.Content> contents
    ) {

        List<SemanticUnit> semanticUnits = new ArrayList<>();
        for (ClaudeConversationJsonDTO.Content content : contents) {
            if (content == null)
                continue;

            String type = content.getType();

            switch (type) {
                case "text" -> {
                    if (content.getText() != null) {
                        semanticUnits.add(
                                SemanticUnit.builder()
                                        .semanticContentType(SemanticContentType.TEXT)
                                        .extractedText(content.getText())
                                        .rawContent(objectMapper.valueToTree(
                                                normalizeContent(
                                                        content.getText()
                                                )
                                        ))
                                        .build()
                        );
                    }
                    break;
                }


                case "tool_use" -> {

                    JsonNode inputNode =
                            objectMapper.valueToTree(content.getInput());

                    String artifactType = null;
                    String language = null;

                    if (inputNode.has("type")) {
                        artifactType = inputNode.get("type").asText();
                    }

                    if (inputNode.has("language")) {
                        language = inputNode.get("language").asText();
                    }

                    if (inputNode.has("content")) {

                        String extractedCode =
                                inputNode.get("content").asText();

                        semanticUnits.add(
                                SemanticUnit.builder()
                                        .semanticContentType(
                                                SemanticContentType.CODE
                                        )
                                        .extractedText(extractedCode)
                                        .rawContent(inputNode)
                                        .metadata(
                                                SemanticUnit.Metadata.builder()
                                                        .language(language)
                                                        .artifactType(artifactType)
                                                        .build()
                                        )
                                        .build()
                        );
                    }
                    break;
                }
//                default -> {
//                    if (content.getText() != null) {
//
//                        semanticUnits.add(
//                                SemanticUnit.builder()
//                                        .semanticContentType(SemanticContentType.TEXT)
//                                        .extractedText(
//                                                normalizeContent(content.getText())
//                                        )
//                                        .rawContent(
//                                                objectMapper.valueToTree(
//                                                        normalizeContent(
//                                                                content.getText()
//                                                        )
//                                                )
//                                        )
//                                        .build()
//                        );
//                    }
//                }
            }
        }
        return semanticUnits;
    }

    private String hashConversation(
            ClaudeConversationJsonDTO dto
    ) {

        try {

            String normalized =
                    objectMapper.writeValueAsString(dto);

            return sha256(normalized);

        } catch (Exception e) {

            throw new RuntimeException(e);
        }
    }

    private String hashMessage(
            String role,
            String content
    ) {

        return sha256(
                role + "::" + normalizeContent(content)
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

