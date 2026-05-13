package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.models.AffiliatedEmail;
import com.NeuroIndex.entity.models.LLm;
import com.NeuroIndex.entity.models.User;
import com.NeuroIndex.parser.dtos.ExtractionFileDTO;
import com.NeuroIndex.parser.dtos.UserJsonDTO;
import com.NeuroIndex.parser.repositories.AffiliatedEmailsRepo;
import com.NeuroIndex.parser.repositories.LlmsRepo;
import com.NeuroIndex.parser.repositories.ProjectConversationDocsRepo;
import com.NeuroIndex.parser.repositories.UserRepo;
import com.NeuroIndex.parser.service.UserFileIngestionService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@Log4j2
public class UserFileIngestionServiceImpl implements UserFileIngestionService {

    private final UserRepo userRepo;
    private final AffiliatedEmailsRepo affiliatedEmailsRepo;
    private final LlmsRepo llmsRepo;
    private final ProjectConversationDocsRepo projectConversationDocsRepo;
    private final ObjectMapper objectMapper;

    public UserFileIngestionServiceImpl(UserRepo userRepo,
                                        AffiliatedEmailsRepo affiliatedEmailsRepo,
                                        LlmsRepo llmsRepo,
                                        ProjectConversationDocsRepo projectConversationDocsRepo,
                                        ObjectMapper objectMapper) {
        this.userRepo = userRepo;
        this.affiliatedEmailsRepo = affiliatedEmailsRepo;
        this.llmsRepo = llmsRepo;
        this.projectConversationDocsRepo = projectConversationDocsRepo;
        this.objectMapper = objectMapper;
    }
    @Override
    public void extractInfo(ExtractionFileDTO extractionFileDTO) throws IOException {
        User user = userRepo.getUserByEmail(extractionFileDTO.getEmail());
        MultipartFile multipartFile = extractionFileDTO.getUserJson();
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
}
