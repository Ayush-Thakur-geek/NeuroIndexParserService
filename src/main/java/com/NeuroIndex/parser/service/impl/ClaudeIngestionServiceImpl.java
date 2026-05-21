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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Service
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

            String conversationHash =
                    hashConversation(dto);

            boolean exists =
                    this.conversationRepo
                            .existsByHash(conversationHash);

            if (exists) {
                continue;
            }

            Conversation conversation =
                    buildConversation(
                            affiliatedEmail,
                            dto,
                            conversationHash
                    );

            List<Message> messages = new ArrayList<>();
            try {
                 messages = buildMessages(dto, conversation);
            } catch (JsonProcessingException e) {
                throw new CustomException(e.getMessage(), "FAILING_RAW_JSON_CONVERSION", 500);
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

        if (chatMessages == null) {
            return messages;
        }

        int sequence = 0;

        for (ClaudeConversationJsonDTO.ChatMessage chatMessage : chatMessages) {

            String prompt =
                    normalizeContent(
                            chatMessage.getText()
                    );

            List<ClaudeConversationJsonDTO.Content> contents =
                    chatMessage.getContent();

            if ((prompt == null || prompt.isBlank())
                    && (contents == null || contents.isEmpty())) {
                continue;
            }

            String assistantContent =
                    extractSemanticText(contents);

            String semanticContent =
                    buildSemanticContent(
                            prompt,
                            assistantContent
                    );

            String rawJson =
                    objectMapper.writeValueAsString(contents);

            String messageHash =
                    hashMessage(
                            chatMessage.getSender(),
                            semanticContent
                    );

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

    private String extractSemanticText(
            List<ClaudeConversationJsonDTO.Content> contents
    ) {

        if (contents == null || contents.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (ClaudeConversationJsonDTO.Content content : contents) {

            if (content == null) {
                continue;
            }

            if ("text".equals(content.getType())
                    && content.getText() != null) {

                builder.append(content.getText())
                        .append("\n\n");
            }

            if (content.getThinking() != null) {

                builder.append(content.getThinking())
                        .append("\n\n");
            }

            if (content.getMessage() != null) {

                builder.append(content.getMessage())
                        .append("\n\n");
            }
        }

        return normalizeContent(builder.toString());
    }

    private String buildSemanticContent(
            String prompt,
            String assistantContent
    ) {

        StringBuilder builder = new StringBuilder();

        if (prompt != null && !prompt.isBlank()) {

            builder.append("USER:\n")
                    .append(prompt)
                    .append("\n\n");
        }

        if (assistantContent != null
                && !assistantContent.isBlank()) {

            builder.append("ASSISTANT:\n")
                    .append(assistantContent);
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
            return null;
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

