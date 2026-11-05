package com.ailearning.activity_service.dto.request;

import com.ailearning.activity_service.enums.ActivityType;
import com.ailearning.activity_service.enums.DifficultyLevel;
import com.ailearning.activity_service.enums.GradeLevel;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityGenerationRequest {

    private Long moduleId;

    private Long courseId;

    private GradeLevel gradeLevel;

    private String subject;

    private String topic;

    private ActivityType activityType;

    private DifficultyLevel difficultyLevel;

    private Integer questionCount;

    private String lessonContent;

    private Boolean saveToModule;
}
