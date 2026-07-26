package com.zihan.zhiwei.ai.intent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClarifyOption {
    private String intent;
    private String label;
    private String question;
    private String description;
    private List<SubOption> subOptions;

    @Data
    @Builder
    public static class SubOption {
        private String code;
        private String label;
    }
}
