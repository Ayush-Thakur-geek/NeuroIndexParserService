package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.models.AffiliatedEmail;
import com.NeuroIndex.entity.models.Conversation;
import com.NeuroIndex.entity.models.Message;
import com.NeuroIndex.parser.dtos.ClaudeConversationJsonDTO;
import com.NeuroIndex.parser.exception.CustomException;
import com.NeuroIndex.parser.repositories.AffiliatedEmailsRepo;
import com.NeuroIndex.parser.repositories.ConversationRepo;
import com.NeuroIndex.parser.repositories.MessageRepo;
import com.NeuroIndex.parser.service.ClaudeIngestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Service
@Log4j2
public class ClaudeIngestionServiceImpl implements ClaudeIngestionService {

    private final ConversationRepo conversationRepo;
    private final MessageRepo messageRepo;
    private final AffiliatedEmailsRepo affiliatedEmailRepo;
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;

    ClaudeIngestionServiceImpl(ConversationRepo conversationRepo,
                               MessageRepo messageRepo,
                               AffiliatedEmailsRepo affiliatedEmailRepo,
                               ExecutorService executorService,
                               ObjectMapper objectMapper) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.affiliatedEmailRepo = affiliatedEmailRepo;
        this.executorService = executorService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void ingestClaudeConversations(
            AffiliatedEmail affiliatedEmail,
            List<ClaudeConversationJsonDTO> conversationsDTO
    ) {

        for (ClaudeConversationJsonDTO dto : conversationsDTO) {

            String conversationHash = hashConversation(dto);

            boolean exists =
                    conversationRepo.existsByHash(conversationHash);

            if (exists) {
                continue;
            }

            Conversation conversation =
                    buildConversation(
                            affiliatedEmail,
                            dto,
                            conversationHash
                    );

            List<Message> messages;

            try {

                messages = buildMessages(
                        dto,
                        conversation
                );

            } catch (Exception e) {

                throw new CustomException(
                        e.getMessage(),
                        "CLAUDE_MESSAGE_INGESTION_FAILED",
                        500
                );
            }

            conversation.setMessages(messages);

            conversationRepo.save(conversation);
        }
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
            Conversation conversation
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

            String semanticContent =
                    extractSemanticContent(contents);

            if (semanticContent.isBlank()) {
                continue;
            }

            String rawJson =
                    objectMapper.writeValueAsString(contents);

            String messageHash =
                    hashMessage(
                            chatMessage.getSender(),
                            semanticContent
                    );

            if (messageRepo.existsByHash(messageHash)) {
                log.info("ALERT! hash clash for sequence: {}", sequence);
            }

            Message message = Message.builder()
                    .conversation(conversation)
                    .sequence_number(sequence++)
                    .role(chatMessage.getSender())
                    .content(semanticContent)
                    .rawJsonContent(rawJson)
                    .hash(messageHash)
                    .build();

            messages.add(message);
        }

        return messages;
    }

    private String extractSemanticContent(
            List<ClaudeConversationJsonDTO.Content> contents
    ) {

        StringBuilder builder = new StringBuilder();

        for (ClaudeConversationJsonDTO.Content content : contents) {

            if (content == null) {
                continue;
            }

            String type = content.getType();

            switch (type) {

                case "text" -> {

                    if (content.getText() != null) {

                        builder.append(content.getText())
                                .append("\n\n");
                    }
                }

                case "thinking" -> {

                    if (content.getThinking() != null) {

                        builder.append(content.getThinking())
                                .append("\n\n");
                    }
                }

//                case "tool_result" -> {
//
//                    if (content.getContent() != null) {
//
//                        for (JsonNode node : content.getContent()) {
//
//                            if (node.has("text")) {
//
//                                builder.append(
//                                                node.get("text").asText()
//                                        )
//                                        .append("\n\n");
//                            }
//                        }
//                    }
//                }

                case "tool_use" -> {

                    if (content.getName() != null) {

                        builder.append("Tool Used: ")
                                .append(content.getName())
                                .append("\n\n");
                    }

                    if (content.getMessage() != null) {

                        builder.append(content.getMessage())
                                .append("\n\n");
                    }
                }

                default -> {

                    if (content.getText() != null) {

                        builder.append(content.getText())
                                .append("\n\n");
                    }
                }
            }
        }

        return normalizeContent(builder.toString());
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

