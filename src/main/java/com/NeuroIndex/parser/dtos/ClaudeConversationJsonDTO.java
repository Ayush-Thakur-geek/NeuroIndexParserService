package com.NeuroIndex.parser.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ClaudeConversationJsonDTO {

    @JsonProperty("uuid")
    private String uuid;

    @JsonProperty("name")
    private String name;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    @JsonProperty("account")
    private Account account;

    @JsonProperty("chat_messages")
    private List<ChatMessage> chatMessages;

    // -------------------------------------------------------------------------
    // Account
    // -------------------------------------------------------------------------

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Account {

        @JsonProperty("uuid")
        private String uuid;

        @JsonProperty("email")
        private String email;
    }

    // -------------------------------------------------------------------------
    // ChatMessage
    // -------------------------------------------------------------------------

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class ChatMessage {

        @JsonProperty("uuid")
        private String uuid;

        /** Links this message to its parent in the conversation tree. */
        @JsonProperty("parent_message_uuid")
        private String parentMessageUuid;

        /** Ordering index within the conversation. */
        @JsonProperty("index")
        private Integer index;

        @JsonProperty("text")
        private String text;

        /** "human" or "assistant" */
        @JsonProperty("sender")
        private String sender;

        @JsonProperty("created_at")
        private Instant createdAt;

        @JsonProperty("updated_at")
        private Instant updatedAt;

        @JsonProperty("content")
        private List<Content> content;

        /** Uploaded file metadata attached by the user. */
        @JsonProperty("attachments")
        private List<Attachment> attachments;

        /** Image / document file references. */
        @JsonProperty("files")
        private List<FileReference> files;
    }

    // -------------------------------------------------------------------------
    // Content  (one block inside a message)
    // -------------------------------------------------------------------------

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Content {

        @JsonProperty("type")
        private String type;

        @JsonProperty("id")
        private String id;

        /** Present on type="text" blocks. */
        @JsonProperty("text")
        private String text;

        /** Present on type="thinking" blocks (extended thinking). */
        @JsonProperty("thinking")
        private String thinking;

        /** Present on tool_result blocks. */
        @JsonProperty("result")
        private String result;

        /** Tool / MCP display name. */
        @JsonProperty("name")
        private String name;

        @JsonProperty("input")
        private Input input;

        @JsonProperty("message")
        private String message;

        @JsonProperty("start_timestamp")
        private Instant startTimestamp;

        @JsonProperty("stop_timestamp")
        private Instant stopTimestamp;

        /** List of flag strings (e.g. ["beta"]). */
        @JsonProperty("flags")
        private List<String> flags;

        @JsonProperty("integration_name")
        private String integrationName;

        @JsonProperty("integration_icon_url")
        private String integrationIconUrl;

        @JsonProperty("icon_name")
        private String iconName;

        @JsonProperty("context")
        private String context;

        @JsonProperty("display_content")
        private String displayContent;

        @JsonProperty("approval_options")
        private List<String> approvalOptions;

        @JsonProperty("approval_key")
        private String approvalKey;

        /** Correct type: boolean, not String. */
        @JsonProperty("is_mcp_app")
        private Boolean isMcpApp;

        @JsonProperty("mcp_server_url")
        private String mcpServerUrl;
    }

    // -------------------------------------------------------------------------
    // Input  (inside a tool-use Content block)
    // -------------------------------------------------------------------------

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Input {

        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;

        @JsonProperty("title")
        private String title;

        @JsonProperty("command")
        private String command;

        @JsonProperty("content")
        private String content;

        @JsonProperty("language")
        private String language;

        /** Camelcase field name; JSON key preserved via @JsonProperty. */
        @JsonProperty("version_uuid")
        private String versionUuid;
    }

    // -------------------------------------------------------------------------
    // Attachment  (user-uploaded file metadata on a ChatMessage)
    // -------------------------------------------------------------------------

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Attachment {

        @JsonProperty("id")
        private String id;

        @JsonProperty("file_name")
        private String fileName;

        @JsonProperty("file_size")
        private Long fileSize;

        @JsonProperty("file_type")
        private String fileType;

        @JsonProperty("extracted_content")
        private String extractedContent;
    }

    // -------------------------------------------------------------------------
    // FileReference  (image / document reference on a ChatMessage)
    // -------------------------------------------------------------------------

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class FileReference {

        @JsonProperty("file_uuid")
        private String fileUuid;

        @JsonProperty("file_name")
        private String fileName;

        @JsonProperty("file_type")
        private String fileType;

        @JsonProperty("file_size")
        private Long fileSize;

        @JsonProperty("created_at")
        private Instant createdAt;
    }
}
