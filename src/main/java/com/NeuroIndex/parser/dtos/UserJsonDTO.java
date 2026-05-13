package com.NeuroIndex.parser.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserJsonDTO {
    @JsonProperty("uuid")
    private String uuid;

    @JsonProperty("full_name")
    private String fullName;

    @JsonProperty("email_address")
    private String emailAddress;

    @JsonProperty("verified_phone_number")
    private String verifiedPhoneNumber;
}
