package com.example.azuremanagement.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class MyReference {
    @SerializedName("@odata.id")
    private String odataId;
}
