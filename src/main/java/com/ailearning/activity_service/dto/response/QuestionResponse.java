package com.ailearning.activity_service.dto.response;

import lombok.*;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionResponse {

    private String questionText;

    private String instruction;

    private Integer points;

    private Integer displayOrder;

    private List<OptionResponse> options;

    private String correctAnswer;

    private String activityType;

    private String icon;

    private String scenario;

    private List<String> keywords;

    private List<String> solutionSteps;

    private List<Map<String, Object>> leftItems;

    private List<Map<String, Object>> rightItems;

    private Map<String, String> matchingAnswers;

    private String sortMode;

    private List<String> categories;

    private List<Object> items;

    private List<String> itemsToOrder;

    private String imageUrl;

    private String imageDescription;

    private String correctRegion;

    private List<Map<String, Object>> regions;

    private String passage;

    private Integer timeLimitSeconds;

    private String correctFeedback;

    private String incorrectFeedback;
}
