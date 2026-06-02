package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.enums.LlmTypes;
import com.NeuroIndex.entity.models.AffiliatedEmail;
import com.NeuroIndex.entity.models.Conversation;
import com.NeuroIndex.entity.models.LLm;
import com.NeuroIndex.entity.models.User;
import com.NeuroIndex.parser.config.ExecutorServiceConfig;
import com.NeuroIndex.parser.dtos.ClaudeConversationJsonDTO;
import com.NeuroIndex.parser.dtos.ExtractionFileDTO;
import com.NeuroIndex.parser.dtos.UserJsonDTO;
import com.NeuroIndex.parser.exception.CustomException;
import com.NeuroIndex.parser.repositories.AffiliatedEmailsRepo;
import com.NeuroIndex.parser.repositories.LlmsRepo;
import com.NeuroIndex.parser.repositories.UserRepo;
import com.NeuroIndex.parser.service.ClaudeIngestionService;
import com.NeuroIndex.parser.service.ExportFileIngestionService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@Log4j2
public class ExportFileIngestionServiceImpl implements ExportFileIngestionService {

    private final UserRepo userRepo;
    private final AffiliatedEmailsRepo affiliatedEmailsRepo;
    private final LlmsRepo llmsRepo;
    private final ObjectMapper objectMapper;
    private final ClaudeIngestionService claudeIngestionService;

    public ExportFileIngestionServiceImpl(UserRepo userRepo,
                                          AffiliatedEmailsRepo affiliatedEmailsRepo,
                                          LlmsRepo llmsRepo,
                                          ObjectMapper objectMapper,
                                          ClaudeIngestionService claudeIngestionService) {
        this.userRepo = userRepo;
        this.affiliatedEmailsRepo = affiliatedEmailsRepo;
        this.llmsRepo = llmsRepo;
        this.objectMapper = objectMapper;
        this.claudeIngestionService = claudeIngestionService;
    }
    @Override
    public void extractUserInfo(ExtractionFileDTO extractionFileDTO) throws IOException {
        User user = userRepo.getUserByEmail(extractionFileDTO.getEmail());
        MultipartFile multipartFile = extractionFileDTO.getJsonFile();
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        if (multipartFile != null) {
            log.info("Extracting file content from file {}", multipartFile.getOriginalFilename());
            List<UserJsonDTO> userJsonDTOList = objectMapper.readValue(
                    multipartFile.getInputStream(),
                    new TypeReference<List<UserJsonDTO>>() {}
            );

            UserJsonDTO userJsonDTO = userJsonDTOList.getFirst();
            AffiliatedEmail affiliatedEmail = AffiliatedEmail.builder()
                    .email(
                            userJsonDTO.getEmailAddress()
                                    .trim()
                                    .toLowerCase()
                    )
                    .uuid(userJsonDTO.getUuid())
                    .build();
            List<AffiliatedEmail> affiliatedEmailList =  new ArrayList<>();
            affiliatedEmailList.add(affiliatedEmail);
            LLm llm = LLm.builder()
                    .user(user)
                    .affiliatedEmails(affiliatedEmailList)
                    .provider(extractionFileDTO.llmTypes)
                    .build();

            affiliatedEmail.setLlm(llm);
            List<LLm>  llmList =  new ArrayList<>();
            llmList.add(llm);
            user.setLlms(llmList);
            userRepo.save(user);
        } else {
            log.info("File is empty");
        }
    }

    @Override
    public void parseConversationExportFile(ExtractionFileDTO extractionFileDTO) throws IOException {
        User user = userRepo.getUserByEmail(extractionFileDTO.getEmail());
        MultipartFile jsonFile = extractionFileDTO.getJsonFile();
        LlmTypes llmType = extractionFileDTO.getLlmTypes();
        if (llmType == LlmTypes.CLAUDE) {
            log.info("Claude parsing for file: {}", jsonFile.getOriginalFilename());
            parsingClaudeConversationFile(user, jsonFile);
        } else if (llmType == LlmTypes.CHAT_GPT) {
            log.info("Chat_gpt parsing for file: {}", jsonFile.getOriginalFilename());
        } else {
            log.info("Gemini parsing for file: {}", jsonFile.getOriginalFilename());
        }
    }

    private void parsingClaudeConversationFile(
            User user,
            MultipartFile jsonFile
    ) throws IOException {

        try {

            MappingIterator<ClaudeConversationJsonDTO> iterator = objectMapper
                    .readerFor(ClaudeConversationJsonDTO.class)
                    .readValues(jsonFile.getInputStream());

            List<ClaudeConversationJsonDTO> batch = new ArrayList<>();

            String accountUuid = null;

            LLm llm = null;

            AffiliatedEmail affiliatedEmail = null;

            int noBatches = 0;

            while (iterator.hasNext()) {

                batch.add(iterator.next());

                if (batch.size() == 20 || !iterator.hasNext()) {
                    noBatches++;

                    if (affiliatedEmail == null) {

                        ClaudeConversationJsonDTO claudeConversationJsonDTO = batch
                                .stream()
                                .filter(dto ->
                                        dto.getAccount() != null
                                                && dto.getAccount().getUuid() != null
                                )
                                .findFirst()
                                .orElse(null);

                        if (claudeConversationJsonDTO == null) {

                            batch.clear();

                            continue;
                        }

                        accountUuid = claudeConversationJsonDTO
                                .getAccount()
                                .getUuid();

                        affiliatedEmail = affiliatedEmailsRepo.findByUuid(accountUuid);

                        if (affiliatedEmail == null) {

//                            throw new CustomException(
//                                    "Conversation export does not belong to known affiliated account",
//                                    "ACCOUNT_IDENTITY_MISMATCH",
//                                    400
//                            );
                        }

                        llm = llmsRepo.findByAffiliatedEmailObject(affiliatedEmail);

                        if (llm == null) {

//                            throw new CustomException(
//                                    "Linked llm provider not found",
//                                    "LINKED_PROVIDER_NOT_FOUND",
//                                    404
//                            );
                        }
                    }

                    log.info(
                            "Ingesting Batch no: {}",
                            noBatches
                    );

                    claudeIngestionService.ingestClaudeConversations(affiliatedEmail, batch);

                    batch.clear();
                }
            }

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {

            throw new CustomException(
                    e.getMessage(),
                    "INGESTION_FAILED",
                    400,
                    e
            );
        }
    }
}
