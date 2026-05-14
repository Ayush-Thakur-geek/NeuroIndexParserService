package com.NeuroIndex.parser.dtos;

import com.NeuroIndex.entity.enums.LlmTypes;
import lombok.*;
import org.springframework.web.multipart.MultipartFile;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ExtractionFileDTO {
    public String email;
    public LlmTypes llmTypes;
    public MultipartFile jsonFile;
}
