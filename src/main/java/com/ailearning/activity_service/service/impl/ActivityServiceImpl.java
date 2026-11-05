package com.ailearning.activity_service.service.impl;

import com.ailearning.activity_service.dto.request.*;
import com.ailearning.activity_service.dto.response.*;
import com.ailearning.activity_service.entity.*;
import com.ailearning.activity_service.enums.*;
import com.ailearning.activity_service.exception.ResourceNotFoundException;
import com.ailearning.activity_service.repository.ActivityRepository;
import com.ailearning.activity_service.repository.StudentActivityAttemptRepository;
import com.ailearning.activity_service.service.ActivityService;
import com.ailearning.activity_service.service.RedisCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

        private final StudentActivityAttemptRepository studentActivityAttemptRepository;

        private final ActivityRepository activityRepository;

        private final RestTemplate restTemplate;

        private final RedisCacheService redisCacheService;

        private final ObjectMapper objectMapper;

        @Value("${ai.service.base-url:http://localhost:8000}")
        private String aiServiceBaseUrl;

        @Value("${course.service.base-url:http://localhost:8082}")
        private String courseServiceBaseUrl;

        @Value("${learning-resource.service.base-url:http://localhost:8085}")
        private String learningResourceServiceBaseUrl;

        @Value("${app.cache.activity.ttl-seconds:86400}")
        private long cacheTtlSeconds;

        private static String truncate(String str, int maxLen) {
                if (str == null) return null;
                return str.length() <= maxLen ? str : str.substring(0, maxLen);
        }

        private GradeLevel parseGradeLevel(Object raw, GradeLevel fallback) {
                if (raw == null) return fallback;
                String norm = raw.toString().trim().toUpperCase().replace(" ", "_").replace("-", "_");
                if (norm.matches("\\d+")) {
                        norm = "GRADE_" + norm;
                }
                try {
                        return GradeLevel.valueOf(norm);
                } catch (Exception ignored) {
                        return fallback;
                }
        }

        private void createDragMappings(
                        Question question,
                        QuestionCreateRequest request) {

                if (request.dragMappings() == null) {
                        return;
                }

                for (DragMappingCreateRequest mappingRequest : request.dragMappings()) {

                        int dragItemIndex = mappingRequest.dragItemIndex() - 1;
                        int dropZoneIndex = mappingRequest.dropZoneIndex() - 1;

                        if (dragItemIndex < 0 ||
                                        dragItemIndex >= question.getDragItems().size()) {

                                throw new IllegalArgumentException(
                                                "Invalid drag item index: "
                                                                + mappingRequest.dragItemIndex());
                        }

                        if (dropZoneIndex < 0 ||
                                        dropZoneIndex >= question.getDropZones().size()) {

                                throw new IllegalArgumentException(
                                                "Invalid drop zone index: "
                                                                + mappingRequest.dropZoneIndex());
                        }

                        DragItem dragItem = question.getDragItems().get(dragItemIndex);

                        DropZone dropZone = question.getDropZones().get(dropZoneIndex);

                        DragMapping mapping = DragMapping.builder()
                                        .dragItem(dragItem)
                                        .dropZone(dropZone)
                                        .build();

                        dragItem.getDragMappings().add(mapping);
                        dropZone.getDragMappings().add(mapping);
                }
        }

        @Override
        public ActivityGenerationResponse generateActivity(ActivityGenerationRequest request) {
                // 1. Resolve subject, course, grade, and topic from Course Service
                String subject = request.getSubject();
                String topic = request.getTopic();
                GradeLevel resolvedGrade = request.getGradeLevel() != null ? request.getGradeLevel() : GradeLevel.GRADE_3;
                String moduleDescription = null;

                if (request.getModuleId() != null) {
                        try {
                                String moduleUrl = courseServiceBaseUrl + "/api/v1/modules/" + request.getModuleId();
                                ResponseEntity<Map> moduleResp = restTemplate.getForEntity(moduleUrl, Map.class);
                                if (moduleResp.getStatusCode().is2xxSuccessful() && moduleResp.getBody() != null) {
                                        Map body = moduleResp.getBody();
                                        Map data = body.containsKey("data") && body.get("data") instanceof Map
                                                        ? (Map) body.get("data")
                                                        : body;
                                        if (topic == null || topic.isBlank()) {
                                                topic = (String) data.get("title");
                                        }
                                        moduleDescription = (String) data.get("description");

                                        Object courseIdObj = data.get("courseId") != null ? data.get("courseId") : request.getCourseId();
                                        if (courseIdObj != null) {
                                                Long courseId = Long.valueOf(courseIdObj.toString());
                                                String courseUrl = courseServiceBaseUrl + "/api/v1/courses/" + courseId;
                                                ResponseEntity<Map> courseResp = restTemplate.getForEntity(courseUrl, Map.class);
                                                if (courseResp.getStatusCode().is2xxSuccessful() && courseResp.getBody() != null) {
                                                        Map cData = courseResp.getBody();
                                                        Map cBody = cData.containsKey("data") && cData.get("data") instanceof Map
                                                                        ? (Map) cData.get("data")
                                                                        : cData;
                                                        if (request.getGradeLevel() == null && cBody.get("gradeLevel") != null) {
                                                                resolvedGrade = parseGradeLevel(cBody.get("gradeLevel"), resolvedGrade);
                                                        }
                                                        Object subjectIdObj = cBody.get("subjectId");
                                                        if ((subject == null || subject.isBlank()) && subjectIdObj != null) {
                                                                Long subjectId = Long.valueOf(subjectIdObj.toString());
                                                                String subjectUrl = courseServiceBaseUrl + "/api/v1/subjects/" + subjectId;
                                                                ResponseEntity<Map> subjectResp = restTemplate.getForEntity(subjectUrl, Map.class);
                                                                if (subjectResp.getStatusCode().is2xxSuccessful() && subjectResp.getBody() != null) {
                                                                        Map sData = subjectResp.getBody();
                                                                        Map sBody = sData.containsKey("data") && sData.get("data") instanceof Map
                                                                                        ? (Map) sData.get("data")
                                                                                        : sData;
                                                                        subject = (String) sBody.get("name");
                                                                }
                                                        }
                                                }
                                        }
                                }
                        } catch (Exception e) {
                                log.warn("Could not query Course Service for module {}: {}", request.getModuleId(), e.getMessage());
                        }
                }

                if (subject == null || subject.isBlank()) {
                        subject = "Science";
                }
                if (topic == null || topic.isBlank()) {
                        topic = "General Concepts";
                }

                // 2. Retrieve module content from request + Learning Resources Service
                StringBuilder lessonContentBuilder = new StringBuilder();
                if (request.getLessonContent() != null && !request.getLessonContent().isBlank()) {
                        lessonContentBuilder.append(request.getLessonContent().trim()).append("\n\n");
                }
                if (moduleDescription != null && !moduleDescription.isBlank()) {
                        lessonContentBuilder.append("Module Overview: ").append(moduleDescription).append("\n\n");
                }

                if (request.getModuleId() != null) {
                        try {
                                String resUrl = learningResourceServiceBaseUrl + "/api/resources/module/" + request.getModuleId();
                                ResponseEntity<List> resResp = restTemplate.getForEntity(resUrl, List.class);
                                if (resResp.getStatusCode().is2xxSuccessful() && resResp.getBody() != null) {
                                        List<?> list = resResp.getBody();
                                        for (Object item : list) {
                                                if (item instanceof Map<?, ?> map) {
                                                        String rTitle = (String) map.get("title");
                                                        String rDesc = (String) map.get("description");
                                                        String rContent = (String) map.get("content");
                                                        if (rTitle != null && !rTitle.isBlank()) {
                                                                lessonContentBuilder.append("Slide: ").append(rTitle).append("\n");
                                                        }
                                                        if (rDesc != null && !rDesc.isBlank()) {
                                                                lessonContentBuilder.append("Description: ").append(rDesc).append("\n");
                                                        }
                                                        if (rContent != null && !rContent.isBlank()) {
                                                                lessonContentBuilder.append("Content: ").append(rContent).append("\n\n");
                                                        }
                                                }
                                        }
                                }
                        } catch (Exception e) {
                                log.warn("Could not query Learning Resource Service for module {}: {}", request.getModuleId(), e.getMessage());
                        }

                        // Also check AI Content chunks if available
                        try {
                                String aiContentUrl = learningResourceServiceBaseUrl + "/api/resources/ai/module/" + request.getModuleId();
                                ResponseEntity<Map> aiResResp = restTemplate.getForEntity(aiContentUrl, Map.class);
                                if (aiResResp.getStatusCode().is2xxSuccessful() && aiResResp.getBody() != null) {
                                        Map body = aiResResp.getBody();
                                        Object resources = body.get("resources");
                                        if (resources instanceof List<?> rList) {
                                                for (Object rObj : rList) {
                                                        if (rObj instanceof Map<?, ?> rMap) {
                                                                Object chunks = rMap.get("chunks");
                                                                if (chunks instanceof List<?> cList) {
                                                                        for (Object cObj : cList) {
                                                                                if (cObj instanceof Map<?, ?> cMap) {
                                                                                        String cText = (String) cMap.get("content");
                                                                                        if (cText != null && !cText.isBlank()) {
                                                                                                lessonContentBuilder.append(cText).append("\n");
                                                                                        }
                                                                                }
                                                                        }
                                                                }
                                                        }
                                                }
                                        }
                                }
                        } catch (Exception e) {
                                log.debug("AI chunks query note: {}", e.getMessage());
                        }
                }

                String lessonContent = lessonContentBuilder.toString().trim();

                ActivityType actType = request.getActivityType() != null ? request.getActivityType() : ActivityType.MCQ;
                int count = request.getQuestionCount() != null && request.getQuestionCount() > 0
                                ? request.getQuestionCount()
                                : 5;
                DifficultyLevel diffLevel = request.getDifficultyLevel() != null ? request.getDifficultyLevel()
                                : DifficultyLevel.MEDIUM;

                String aiActivityType = switch (actType) {
                        case MCQ -> "MCQ";
                        case QUIZ -> "Quiz";
                        case TIMED_QUIZ -> "TimedQuiz";
                        case TRUE_FALSE -> "TRUE_FALSE";
                        case FILL_BLANK -> "FillInTheBlanks";
                        case MATCHING -> "Matching";
                        case SORTING -> "Sorting";
                        case DRAG_DROP -> "DragDrop";
                        case POLL -> "Poll";
                        case SHORT_ANSWER -> "ShortAnswer";
                        case ESSAY -> "StructuredEssay";
                        case CHALLENGE_QUIZ -> "ChallengeQuiz";
                        case HOTSPOT -> "Hotspot";
                        case PROBLEM_SOLVING -> "ProblemSolving";
                        case APPLICATION_BASED -> "ApplicationBased";
                };

                boolean shouldSaveToModule = request.getModuleId() != null
                                && (request.getSaveToModule() == null || Boolean.TRUE.equals(request.getSaveToModule()));

                // 3. Check Redis Cache
                String cacheKey = String.format("activity:gen:v2:%s:%s:%s:%s:%d:%s",
                                subject.trim().toLowerCase(),
                                topic.trim().toLowerCase(),
                                resolvedGrade.name(),
                                aiActivityType.toLowerCase(),
                                count,
                                diffLevel.name());

                String cachedJson = redisCacheService.get(cacheKey);
                if (cachedJson != null && !cachedJson.isBlank()) {
                        try {
                                log.info("Redis cache HIT for key: {}", cacheKey);
                                ActivityGenerationResponse cachedResp = objectMapper.readValue(cachedJson, ActivityGenerationResponse.class);
                                if (shouldSaveToModule) {
                                        persistGeneratedActivityToModule(request.getModuleId(), cachedResp);
                                }
                                return cachedResp;
                        } catch (Exception e) {
                                log.warn("Failed to deserialize cached activity: {}", e.getMessage());
                        }
                }

                // 4. Call Multi-Agent Setup
                List<QuestionResponse> questions = new ArrayList<>();
                int totalMarks = 0;

                try {
                        Map<String, Object> aiPayload = new HashMap<>();
                        aiPayload.put("subject", subject);
                        aiPayload.put("topic", topic);
                        aiPayload.put("activityType", aiActivityType);
                        aiPayload.put("numberOfQuestions", count);
                        aiPayload.put("difficultyLevel", diffLevel.name());
                        aiPayload.put("gradeLevel", resolvedGrade.name());
                        if (!lessonContent.isBlank()) {
                                aiPayload.put("lessonContent", lessonContent);
                        }

                        String aiUrl = aiServiceBaseUrl + "/generate-activity";
                        log.info("Calling Multi-Agent Setup at {} for grade='{}', subject='{}', topic='{}', type='{}', hasLessonContent={}",
                                        aiUrl, resolvedGrade, subject, topic, aiActivityType, !lessonContent.isBlank());

                        ResponseEntity<Map> aiResp = restTemplate.postForEntity(aiUrl, aiPayload, Map.class);
                        if (aiResp.getStatusCode().is2xxSuccessful() && aiResp.getBody() != null) {
                                Map respBody = aiResp.getBody();
                                Object rawQuestions = respBody.get("questions");
                                if (rawQuestions instanceof List<?> qList) {
                                        int order = 1;
                                        for (Object qObj : qList) {
                                                if (qObj instanceof Map<?, ?> qMap) {
                                                        String qText = qMap.get("question") != null
                                                                        ? qMap.get("question").toString()
                                                                        : "Question " + order;

                                                        String scenario = qMap.get("scenario") != null
                                                                        ? qMap.get("scenario").toString()
                                                                        : null;

                                                        String answer = null;
                                                        if (qMap.get("answer") != null) {
                                                                answer = qMap.get("answer").toString();
                                                        } else if (qMap.get("expectedAnswer") != null) {
                                                                answer = qMap.get("expectedAnswer").toString();
                                                        } else if (qMap.get("correctAnswer") != null) {
                                                                answer = qMap.get("correctAnswer").toString();
                                                        } else if (qMap.get("correct_answer") != null) {
                                                                answer = qMap.get("correct_answer").toString();
                                                        } else if (qMap.get("correctRegion") != null) {
                                                                answer = qMap.get("correctRegion").toString();
                                                        }

                                                        // Ensure FILL_BLANK has proper blank formatting
                                                        if (actType == ActivityType.FILL_BLANK) {
                                                                if (!qText.contains("______")) {
                                                                        if (qText.contains("___")) {
                                                                                qText = qText.replaceAll("_{3,}", "______");
                                                                        } else if (qText.contains("[blank]")) {
                                                                                qText = qText.replace("[blank]", "______");
                                                                        } else {
                                                                                qText = qText + " ______";
                                                                        }
                                                                }
                                                        }

                                                        List<OptionResponse> optionResponses = new ArrayList<>();
                                                        Object rawOptions = qMap.get("options");
                                                        if (rawOptions instanceof List<?> optList) {
                                                                int optOrder = 1;
                                                                for (Object opt : optList) {
                                                                        if (opt != null) {
                                                                                String optText = opt.toString();
                                                                                optionResponses.add(OptionResponse.builder()
                                                                                                .optionText(optText)
                                                                                                .displayOrder(optOrder++)
                                                                                                .correct(answer != null && answer.trim().equalsIgnoreCase(optText.trim()))
                                                                                                .build());
                                                                        }
                                                                }
                                                        }

                                                        // Ensure True/False has True/False options
                                                        if (actType == ActivityType.TRUE_FALSE && optionResponses.isEmpty()) {
                                                                if (answer == null) answer = "True";
                                                                optionResponses.add(OptionResponse.builder().optionText("True").displayOrder(1).correct("True".equalsIgnoreCase(answer)).build());
                                                                optionResponses.add(OptionResponse.builder().optionText("False").displayOrder(2).correct("False".equalsIgnoreCase(answer)).build());
                                                        }

                                                        String instruction = switch (actType) {
                                                                case TRUE_FALSE -> "Select True or False.";
                                                                case FILL_BLANK -> "Type the missing word in the blank.";
                                                                case MATCHING -> "Match each item on the left with its correct partner on the right.";
                                                                case SORTING -> "Sort each item into the correct category.";
                                                                case DRAG_DROP -> "Arrange the items in the correct sequential order.";
                                                                case HOTSPOT -> "Select the correct region on the diagram.";
                                                                case SHORT_ANSWER -> "Type your concise answer below.";
                                                                case PROBLEM_SOLVING -> "Solve the exercise and provide your answer.";
                                                                case ESSAY -> "Read the passage and write your structured response.";
                                                                case CHALLENGE_QUIZ -> "Type your answer for this challenge.";
                                                                case APPLICATION_BASED -> "Read the scenario and apply your knowledge.";
                                                                case POLL -> "Select your choice.";
                                                                case TIMED_QUIZ -> "Select the correct answer before time expires.";
                                                                default -> "Choose the correct answer.";
                                                        };

                                                        // Keywords & solution steps
                                                        List<String> keywords = null;
                                                        if (qMap.get("keywords") instanceof List<?> kwList) {
                                                                keywords = new ArrayList<>();
                                                                for (Object kw : kwList) if (kw != null) keywords.add(kw.toString());
                                                        }

                                                        List<String> solutionSteps = null;
                                                        if (qMap.get("solutionSteps") instanceof List<?> stepList) {
                                                                solutionSteps = new ArrayList<>();
                                                                for (Object st : stepList) if (st != null) solutionSteps.add(st.toString());
                                                        }

                                                        // MATCHING: leftItems, rightItems, answers
                                                        List<Map<String, Object>> leftItems = null;
                                                        if (qMap.get("leftItems") instanceof List<?> lList) {
                                                                leftItems = new ArrayList<>();
                                                                int idx = 1;
                                                                for (Object li : lList) {
                                                                        if (li instanceof Map<?, ?> lm) {
                                                                                Map<String, Object> entry = new LinkedHashMap<>();
                                                                                entry.put("id", lm.get("id") != null ? lm.get("id").toString() : String.valueOf(idx));
                                                                                entry.put("text", lm.get("text") != null ? lm.get("text").toString() : "");
                                                                                leftItems.add(entry);
                                                                        } else if (li != null) {
                                                                                Map<String, Object> entry = new LinkedHashMap<>();
                                                                                entry.put("id", String.valueOf(idx));
                                                                                entry.put("text", li.toString());
                                                                                leftItems.add(entry);
                                                                        }
                                                                        idx++;
                                                                }
                                                        }

                                                        List<Map<String, Object>> rightItems = null;
                                                        if (qMap.get("rightItems") instanceof List<?> rList) {
                                                                rightItems = new ArrayList<>();
                                                                char label = 'A';
                                                                for (Object ri : rList) {
                                                                        if (ri instanceof Map<?, ?> rm) {
                                                                                Map<String, Object> entry = new LinkedHashMap<>();
                                                                                entry.put("id", rm.get("id") != null ? rm.get("id").toString() : String.valueOf(label));
                                                                                entry.put("text", rm.get("text") != null ? rm.get("text").toString() : "");
                                                                                rightItems.add(entry);
                                                                        } else if (ri != null) {
                                                                                Map<String, Object> entry = new LinkedHashMap<>();
                                                                                entry.put("id", String.valueOf(label));
                                                                                entry.put("text", ri.toString());
                                                                                rightItems.add(entry);
                                                                        }
                                                                        label++;
                                                                }
                                                        }

                                                        Map<String, String> matchingAnswers = null;
                                                        if (qMap.get("answers") instanceof Map<?, ?> ansMap) {
                                                                matchingAnswers = new LinkedHashMap<>();
                                                                for (Map.Entry<?, ?> entry : ansMap.entrySet()) {
                                                                        if (entry.getKey() != null && entry.getValue() != null) {
                                                                                matchingAnswers.put(entry.getKey().toString(), entry.getValue().toString());
                                                                        }
                                                                }
                                                        }

                                                        // SORTING: categories, items, sortMode
                                                        String sortMode = qMap.get("sortMode") != null ? qMap.get("sortMode").toString() : null;
                                                        List<String> categories = null;
                                                        if (qMap.get("categories") instanceof List<?> catList) {
                                                                categories = new ArrayList<>();
                                                                for (Object c : catList) if (c != null) categories.add(c.toString());
                                                        }

                                                        List<Object> items = null;
                                                        if (qMap.get("correctGroups") instanceof Map<?, ?> groups && !groups.isEmpty()) {
                                                                if (categories == null) categories = new ArrayList<>();
                                                                items = new ArrayList<>();
                                                                for (Map.Entry<?, ?> entry : groups.entrySet()) {
                                                                        String catName = entry.getKey().toString();
                                                                        if (!categories.contains(catName)) categories.add(catName);
                                                                        if (entry.getValue() instanceof List<?> groupItems) {
                                                                                for (Object gItem : groupItems) {
                                                                                        if (gItem != null) {
                                                                                                Map<String, Object> itm = new LinkedHashMap<>();
                                                                                                itm.put("name", gItem.toString());
                                                                                                itm.put("category", catName);
                                                                                                itm.put("icon", "twemoji:sparkles");
                                                                                                items.add(itm);
                                                                                        }
                                                                                }
                                                                        }
                                                                }
                                                        } else if (qMap.get("items") instanceof List<?> itmList) {
                                                                items = new ArrayList<>(itmList);
                                                        }

                                                        List<String> itemsToOrder = null;
                                                        if (qMap.get("itemsToOrder") instanceof List<?> ordList) {
                                                                itemsToOrder = new ArrayList<>();
                                                                for (Object o : ordList) if (o != null) itemsToOrder.add(o.toString());
                                                        } else if (qMap.get("correctOrder") instanceof List<?> ordList) {
                                                                itemsToOrder = new ArrayList<>();
                                                                for (Object o : ordList) if (o != null) itemsToOrder.add(o.toString());
                                                        }

                                                        // HOTSPOT: imageUrl, imageDescription, correctRegion, regions
                                                        String imageUrl = qMap.get("imageUrl") != null ? qMap.get("imageUrl").toString() : null;
                                                        if ((imageUrl == null || imageUrl.isBlank()) && actType == ActivityType.HOTSPOT) {
                                                                imageUrl = "https://images.unsplash.com/photo-1530595467537-0b5996c41f2d?auto=format&fit=crop&w=800&q=80";
                                                        }
                                                        String imageDescription = qMap.get("imageDescription") != null ? qMap.get("imageDescription").toString() : null;
                                                        String correctRegion = qMap.get("correctRegion") != null ? qMap.get("correctRegion").toString() : null;
                                                        List<Map<String, Object>> regions = null;
                                                        if (qMap.get("regions") instanceof List<?> regList) {
                                                                regions = new ArrayList<>();
                                                                for (Object rObj : regList) {
                                                                        if (rObj instanceof Map<?, ?> rm) {
                                                                                Map<String, Object> regMap = new LinkedHashMap<>();
                                                                                for (Map.Entry<?, ?> re : rm.entrySet()) {
                                                                                        if (re.getKey() != null) {
                                                                                                regMap.put(re.getKey().toString(), re.getValue());
                                                                                        }
                                                                                }
                                                                                regions.add(regMap);
                                                                        }
                                                                }
                                                        }

                                                        String passage = qMap.get("passage") != null ? qMap.get("passage").toString() : null;
                                                        Integer timeLimitSeconds = qMap.get("timeLimitSeconds") != null
                                                                        ? Integer.valueOf(qMap.get("timeLimitSeconds").toString())
                                                                        : (actType == ActivityType.TIMED_QUIZ ? 20 : null);

                                                        String feedbackText = qMap.get("feedback") != null ? qMap.get("feedback").toString() : null;
                                                        String expectedExplanation = qMap.get("expectedAnswer") != null ? qMap.get("expectedAnswer").toString() : null;

                                                        String correctFeedback = qMap.get("correctFeedback") != null
                                                                        ? qMap.get("correctFeedback").toString()
                                                                        : (feedbackText != null ? feedbackText : "Great job! That is correct!");

                                                        String incorrectFeedback = qMap.get("incorrectFeedback") != null
                                                                        ? qMap.get("incorrectFeedback").toString()
                                                                        : (feedbackText != null
                                                                                        ? feedbackText
                                                                                        : (expectedExplanation != null
                                                                                                        ? "Expected: " + expectedExplanation
                                                                                                        : (answer != null ? "The correct answer is: " + answer : "Review the lesson concepts and try again.")));

                                                        String icon = qMap.get("icon") != null
                                                                        ? qMap.get("icon").toString()
                                                                        : switch (actType) {
                                                                                case TRUE_FALSE -> "twemoji:check-mark-button";
                                                                                case FILL_BLANK -> "twemoji:pencil";
                                                                                case MATCHING -> "twemoji:link";
                                                                                case SORTING -> "twemoji:card-file-box";
                                                                                case DRAG_DROP -> "twemoji:puzzle-piece";
                                                                                case HOTSPOT -> "twemoji:magnifying-glass-tilted-right";
                                                                                case ESSAY -> "twemoji:open-book";
                                                                                case SHORT_ANSWER -> "twemoji:thinking-face";
                                                                                case PROBLEM_SOLVING -> "twemoji:abacus";
                                                                                case APPLICATION_BASED -> "twemoji:light-bulb";
                                                                                case POLL -> "twemoji:bar-chart";
                                                                                case TIMED_QUIZ -> "twemoji:stopwatch";
                                                                                case CHALLENGE_QUIZ -> "twemoji:trophy";
                                                                                default -> "twemoji:sparkles";
                                                                        };

                                                        int points = 2;
                                                        totalMarks += points;

                                                        questions.add(QuestionResponse.builder()
                                                                        .questionText(qText)
                                                                        .instruction(instruction)
                                                                        .points(points)
                                                                        .displayOrder(order++)
                                                                        .correctAnswer(answer)
                                                                        .options(optionResponses)
                                                                        .activityType(actType.name())
                                                                        .scenario(scenario)
                                                                        .keywords(keywords)
                                                                        .solutionSteps(solutionSteps)
                                                                        .leftItems(leftItems)
                                                                        .rightItems(rightItems)
                                                                        .matchingAnswers(matchingAnswers)
                                                                        .sortMode(sortMode)
                                                                        .categories(categories)
                                                                        .items(items)
                                                                        .itemsToOrder(itemsToOrder)
                                                                        .imageUrl(imageUrl)
                                                                        .imageDescription(imageDescription)
                                                                        .correctRegion(correctRegion)
                                                                        .regions(regions)
                                                                        .passage(passage)
                                                                        .timeLimitSeconds(timeLimitSeconds)
                                                                        .icon(icon)
                                                                        .correctFeedback(correctFeedback)
                                                                        .incorrectFeedback(incorrectFeedback)
                                                                        .build());
                                                }
                                        }
                                }
                        }
                } catch (Exception e) {
                        log.warn("Multi-Agent Setup call failed ({}); using intelligent fallback generator.", e.getMessage());
                }

                // 5. Fallback if AI service returned no questions
                if (questions.isEmpty()) {
                        questions = createFallbackQuestions(topic, subject, actType, count);
                        totalMarks = questions.size() * 2;
                }

                String typeLabel = switch (actType) {
                        case MCQ -> "MCQ";
                        case QUIZ -> "Quiz";
                        case TRUE_FALSE -> "True / False";
                        case FILL_BLANK -> "Fill in the Blanks";
                        case MATCHING -> "Match the Following";
                        case SORTING -> "Category Sorting";
                        case DRAG_DROP -> "Drag & Drop Order";
                        case POLL -> "Classroom Poll";
                        case SHORT_ANSWER -> "Short Answer";
                        case ESSAY -> "Structured Essay";
                        case HOTSPOT -> "Hotspot Visual";
                        case PROBLEM_SOLVING -> "Problem Solving";
                        case APPLICATION_BASED -> "Application Scenario";
                        case TIMED_QUIZ -> "Timed Quiz";
                        case CHALLENGE_QUIZ -> "Challenge Quiz";
                };

                ActivityGenerationResponse response = ActivityGenerationResponse.builder()
                                .moduleId(request.getModuleId())
                                .title(truncate(topic + " - " + typeLabel, 150))
                                .description("AI-generated " + typeLabel + " activity for " + topic + " (" + subject + ")")
                                .activityType(actType)
                                .difficulty(diffLevel)
                                .gradeLevel(resolvedGrade)
                                .timeLimitMinutes(actType == ActivityType.TIMED_QUIZ ? 10 : null)
                                .totalMarks(totalMarks)
                                .questions(questions)
                                .build();

                // 6. Store in Redis Cache
                try {
                        redisCacheService.set(cacheKey, objectMapper.writeValueAsString(response), cacheTtlSeconds);
                } catch (Exception e) {
                        log.warn("Failed to write activity to Redis cache: {}", e.getMessage());
                }

                // 7. Persist generated activity under moduleId so Student UI immediately sees it inside the Course & Module
                if (shouldSaveToModule) {
                        persistGeneratedActivityToModule(request.getModuleId(), response);
                }

                return response;
        }

        @Transactional
        protected void persistGeneratedActivityToModule(Long moduleId, ActivityGenerationResponse genResp) {
                if (moduleId == null || genResp == null || genResp.getQuestions() == null || genResp.getQuestions().isEmpty()) {
                        return;
                }
                try {
                        List<Activity> existingInModule = activityRepository.findByModuleId(moduleId);
                        String firstQuestionText = genResp.getQuestions().get(0).getQuestionText();

                        // Check if an identical activity (same type, difficulty, and first question) already exists in this module
                        for (Activity existing : existingInModule) {
                                if (existing.getActivityType() == genResp.getActivityType()
                                                && existing.getDifficulty() == genResp.getDifficulty()
                                                && !existing.getQuestions().isEmpty()
                                                && Objects.equals(existing.getQuestions().get(0).getQuestionText(), firstQuestionText)) {
                                        genResp.setActivityId(existing.getActivityId());
                                        genResp.setModuleId(moduleId);
                                        return;
                                }
                        }

                        int nextOrder = existingInModule.size() + 1;
                        Activity activity = Activity.builder()
                                        .moduleId(moduleId)
                                        .gradeLevel(genResp.getGradeLevel() != null ? genResp.getGradeLevel() : GradeLevel.GRADE_3)
                                        .displayOrder(nextOrder)
                                        .title(truncate(genResp.getTitle() != null ? genResp.getTitle() : "Module Activity", 150))
                                        .description(truncate(genResp.getDescription(), 1000))
                                        .activityType(genResp.getActivityType() != null ? genResp.getActivityType() : ActivityType.MCQ)
                                        .difficulty(genResp.getDifficulty() != null ? genResp.getDifficulty() : DifficultyLevel.MEDIUM)
                                        .timeLimitMinutes(genResp.getTimeLimitMinutes())
                                        .totalMarks(genResp.getTotalMarks() != null ? genResp.getTotalMarks() : genResp.getQuestions().size() * 2)
                                        .status(ActivityStatus.PUBLISHED)
                                        .build();

                        int qOrder = 1;
                        for (QuestionResponse qr : genResp.getQuestions()) {
                                String metaJson = null;
                                try {
                                        metaJson = objectMapper.writeValueAsString(qr);
                                } catch (Exception ignored) {
                                }

                                Question question = Question.builder()
                                                .activity(activity)
                                                .questionText(qr.getQuestionText() != null ? qr.getQuestionText() : "Question " + qOrder)
                                                .instructions(truncate(qr.getInstruction(), 500))
                                                .imageUrl(qr.getImageUrl())
                                                .points(qr.getPoints() != null ? qr.getPoints() : 2)
                                                .displayOrder(qr.getDisplayOrder() != null ? qr.getDisplayOrder() : qOrder)
                                                .metadataJson(metaJson)
                                                .build();

                                // Populate standard relational child entities alongside metadataJson
                                if (qr.getOptions() != null && !qr.getOptions().isEmpty()) {
                                        int optIdx = 1;
                                        for (OptionResponse opt : qr.getOptions()) {
                                                question.getOptions().add(Option.builder()
                                                                .question(question)
                                                                .optionText(opt.getOptionText())
                                                                .correct(Boolean.TRUE.equals(opt.getCorrect()))
                                                                .displayOrder(opt.getDisplayOrder() != null ? opt.getDisplayOrder() : optIdx++)
                                                                .build());
                                        }
                                }

                                if (activity.getActivityType() == ActivityType.FILL_BLANK && qr.getCorrectAnswer() != null) {
                                        question.getFillBlanks().add(FillBlank.builder()
                                                        .question(question)
                                                        .correctAnswer(qr.getCorrectAnswer())
                                                        .blankIndex(1)
                                                        .build());
                                }

                                if (activity.getActivityType() == ActivityType.MATCHING
                                                && qr.getLeftItems() != null
                                                && qr.getRightItems() != null) {
                                        Map<String, String> rightById = new HashMap<>();
                                        for (Map<String, Object> rItem : qr.getRightItems()) {
                                                if (rItem.get("id") != null && rItem.get("text") != null) {
                                                        rightById.put(rItem.get("id").toString(), rItem.get("text").toString());
                                                }
                                        }
                                        int pairOrder = 1;
                                        for (Map<String, Object> lItem : qr.getLeftItems()) {
                                                String lId = lItem.get("id") != null ? lItem.get("id").toString() : String.valueOf(pairOrder);
                                                String lText = lItem.get("text") != null ? lItem.get("text").toString() : "";
                                                String rId = qr.getMatchingAnswers() != null ? qr.getMatchingAnswers().get(lId) : null;
                                                String rText = rId != null ? rightById.getOrDefault(rId, rId) : "";
                                                if (!lText.isBlank() && !rText.isBlank()) {
                                                        question.getMatchingPairs().add(MatchingPair.builder()
                                                                        .question(question)
                                                                        .leftItem(lText)
                                                                        .rightItem(rText)
                                                                        .displayOrder(pairOrder++)
                                                                        .build());
                                                }
                                        }
                                }

                                if (qr.getItemsToOrder() != null && !qr.getItemsToOrder().isEmpty()) {
                                        int sOrder = 1;
                                        for (String sItem : qr.getItemsToOrder()) {
                                                question.getSortingItems().add(SortingItem.builder()
                                                                .question(question)
                                                                .itemText(sItem)
                                                                .correctOrder(sOrder++)
                                                                .build());
                                        }
                                }

                                if (activity.getActivityType() == ActivityType.ESSAY
                                                || activity.getActivityType() == ActivityType.SHORT_ANSWER
                                                || activity.getActivityType() == ActivityType.PROBLEM_SOLVING
                                                || activity.getActivityType() == ActivityType.CHALLENGE_QUIZ) {
                                        question.setEssay(Essay.builder()
                                                        .question(question)
                                                        .gradingRubric(truncate(qr.getCorrectAnswer(), 1000))
                                                        .build());
                                }

                                activity.getQuestions().add(question);
                                qOrder++;
                        }

                        Activity saved = activityRepository.save(activity);
                        genResp.setActivityId(saved.getActivityId());
                        genResp.setModuleId(moduleId);
                        evictActivityCaches(moduleId, saved.getActivityId());
                        log.info("Persisted generated {} activity (id={}) for moduleId={}",
                                        saved.getActivityType(), saved.getActivityId(), moduleId);
                } catch (Exception e) {
                        log.warn("Could not persist generated activity for module {}: {}", moduleId, e.getMessage());
                }
        }

        private List<QuestionResponse> createFallbackQuestions(String topic, String subject, ActivityType actType, int count) {
                List<QuestionResponse> list = new ArrayList<>();
                for (int i = 1; i <= count; i++) {
                        switch (actType) {
                                case TRUE_FALSE -> {
                                        boolean isTrue = (i % 2 != 0);
                                        list.add(QuestionResponse.builder()
                                                        .questionText(isTrue
                                                                        ? String.format("%s is an important concept studied in %s.", topic, subject)
                                                                        : String.format("%s has no connection at all to %s.", topic, subject))
                                                        .instruction("Select True or False.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("TRUE_FALSE")
                                                        .icon("twemoji:check-mark-button")
                                                        .correctAnswer(isTrue ? "True" : "False")
                                                        .options(List.of(
                                                                        OptionResponse.builder().optionText("True").displayOrder(1).correct(isTrue).build(),
                                                                        OptionResponse.builder().optionText("False").displayOrder(2).correct(!isTrue).build()))
                                                        .correctFeedback("Great job! That is correct!")
                                                        .incorrectFeedback("That statement is actually " + (isTrue ? "True" : "False") + ".")
                                                        .build());
                                }
                                case FILL_BLANK -> {
                                        String answer = topic;
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("In %s, the lesson concept of ______ helps explain key observations.", subject))
                                                        .instruction("Type the missing word or phrase in the blank.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("FILL_BLANK")
                                                        .icon("twemoji:pencil")
                                                        .correctAnswer(answer)
                                                        .options(List.of())
                                                        .correctFeedback("Well done! You filled the blank correctly!")
                                                        .incorrectFeedback("The correct answer is \"" + answer + "\".")
                                                        .build());
                                }
                                case MATCHING -> {
                                        List<Map<String, Object>> leftItems = List.of(
                                                        Map.of("id", "1", "text", topic + " Definition"),
                                                        Map.of("id", "2", "text", subject + " Application"),
                                                        Map.of("id", "3", "text", "Key Observation")
                                        );
                                        List<Map<String, Object>> rightItems = List.of(
                                                        Map.of("id", "A", "text", "Core explanation of how " + topic + " works"),
                                                        Map.of("id", "B", "text", "Using " + topic + " in real-world " + subject + " examples"),
                                                        Map.of("id", "C", "text", "Evidence noticed during a " + topic + " activity")
                                        );
                                        Map<String, String> matchingAnswers = Map.of("1", "A", "2", "B", "3", "C");
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Match each %s concept on the left with its description on the right:", topic))
                                                        .instruction("Match each item on the left with its correct partner on the right.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("MATCHING")
                                                        .icon("twemoji:link")
                                                        .leftItems(leftItems)
                                                        .rightItems(rightItems)
                                                        .matchingAnswers(matchingAnswers)
                                                        .correctFeedback("Excellent! All pairs matched accurately!")
                                                        .incorrectFeedback("Review the definitions and try matching the pairs again.")
                                                        .build());
                                }
                                case SHORT_ANSWER, CHALLENGE_QUIZ, PROBLEM_SOLVING -> {
                                        String answer = topic + " is a key concept in " + subject + ".";
                                        List<String> steps = actType == ActivityType.PROBLEM_SOLVING
                                                        ? List.of(
                                                                        "Identify the core rule of " + topic + ".",
                                                                        "Apply the concept to the problem.",
                                                                        "Verify your conclusion.")
                                                        : null;
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Explain a key idea or solve example #%d related to %s in %s.", i, topic, subject))
                                                        .instruction("Type your concise answer below.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType(actType.name())
                                                        .icon(actType == ActivityType.CHALLENGE_QUIZ ? "twemoji:trophy" : (actType == ActivityType.PROBLEM_SOLVING ? "twemoji:abacus" : "twemoji:thinking-face"))
                                                        .correctAnswer(answer)
                                                        .keywords(List.of(topic.toLowerCase(), subject.toLowerCase()))
                                                        .solutionSteps(steps)
                                                        .options(List.of())
                                                        .correctFeedback("Superb answer!")
                                                        .incorrectFeedback("Expected key idea: \"" + answer + "\".")
                                                        .build());
                                }
                                case ESSAY -> {
                                        String passage = String.format("%s is an essential topic in %s. It introduces important definitions, examples, and practical mechanisms that students observe in everyday life.", topic, subject);
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Based on the passage, why is %s important in %s? Explain in 2-3 sentences.", topic, subject))
                                                        .instruction("Read the passage and write your structured response.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("ESSAY")
                                                        .icon("twemoji:open-book")
                                                        .passage(passage)
                                                        .correctAnswer(passage)
                                                        .keywords(List.of(topic.toLowerCase(), subject.toLowerCase()))
                                                        .options(List.of())
                                                        .correctFeedback("Wonderful structured essay response!")
                                                        .incorrectFeedback("Try adding more details from the passage.")
                                                        .build());
                                }
                                case APPLICATION_BASED -> {
                                        String scenario = String.format("During a classroom project on %s, students test how %s works in an everyday setting.", subject, topic);
                                        String coreAns = "Apply the principles of " + topic + " step by step";
                                        list.add(QuestionResponse.builder()
                                                        .scenario(scenario)
                                                        .questionText(String.format("What is the best way for the students to apply %s in Scenario #%d?", topic, i))
                                                        .instruction("Read the scenario and choose the best response.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("APPLICATION_BASED")
                                                        .icon("twemoji:light-bulb")
                                                        .correctAnswer(coreAns)
                                                        .options(List.of(
                                                                        OptionResponse.builder().optionText(coreAns).displayOrder(1).correct(true).build(),
                                                                        OptionResponse.builder().optionText("Ignore the observations completely").displayOrder(2).correct(false).build(),
                                                                        OptionResponse.builder().optionText("Use an unrelated rule without testing").displayOrder(3).correct(false).build(),
                                                                        OptionResponse.builder().optionText("Skip recording any results").displayOrder(4).correct(false).build()))
                                                        .correctFeedback("Spot on application!")
                                                        .incorrectFeedback("The best response was \"" + coreAns + "\".")
                                                        .build());
                                }
                                case POLL -> {
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Which part of %s helps us understand %s best?", topic, subject))
                                                        .instruction("Select your choice.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("POLL")
                                                        .icon("twemoji:bar-chart")
                                                        .correctAnswer("Core concepts and examples")
                                                        .options(List.of(
                                                                        OptionResponse.builder().optionText("Core concepts and examples").displayOrder(1).correct(true).build(),
                                                                        OptionResponse.builder().optionText("Practical hands-on activities").displayOrder(2).correct(false).build(),
                                                                        OptionResponse.builder().optionText("Visual diagrams and charts").displayOrder(3).correct(false).build(),
                                                                        OptionResponse.builder().optionText("Real-world problem solving").displayOrder(4).correct(false).build()))
                                                        .correctFeedback("Thanks for participating!")
                                                        .incorrectFeedback("Thanks for participating!")
                                                        .build());
                                }
                                case SORTING -> {
                                        String cat1 = "Core " + topic;
                                        String cat2 = "Related Examples";
                                        List<String> categories = List.of(cat1, cat2);
                                        List<Object> items = List.of(
                                                        Map.of("name", topic + " Principle A", "category", cat1, "icon", "twemoji:star"),
                                                        Map.of("name", topic + " Principle B", "category", cat1, "icon", "twemoji:sparkles"),
                                                        Map.of("name", subject + " Example A", "category", cat2, "icon", "twemoji:deciduous-tree"),
                                                        Map.of("name", subject + " Example B", "category", cat2, "icon", "twemoji:cloud")
                                        );
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Sort the following items into the correct categories for %s:", topic))
                                                        .instruction("Sort each item into its correct category box.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("SORTING")
                                                        .sortMode("category")
                                                        .icon("twemoji:card-file-box")
                                                        .categories(categories)
                                                        .items(items)
                                                        .correctFeedback("Incredible! All items sorted into the correct categories!")
                                                        .incorrectFeedback("Check your sorted items and try again.")
                                                        .build());
                                }
                                case DRAG_DROP -> {
                                        List<String> orderList = List.of(
                                                        "Observe the starting elements of " + topic,
                                                        "Identify the key relationship in " + subject,
                                                        "Apply the main process carefully",
                                                        "Verify the final outcome"
                                        );
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Arrange the stages of %s in the correct order:", topic))
                                                        .instruction("Drag and arrange items from first to last.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("DRAG_DROP")
                                                        .icon("twemoji:puzzle-piece")
                                                        .itemsToOrder(orderList)
                                                        .correctFeedback("Perfect order! You arranged all steps correctly!")
                                                        .incorrectFeedback("Try arranging the steps again.")
                                                        .build());
                                }
                                case HOTSPOT -> {
                                        List<Map<String, Object>> regions = List.of(
                                                        Map.of("id", "r1", "label", "Upper Section", "x", 50, "y", 20, "width", 24, "height", 16, "isCorrect", false),
                                                        Map.of("id", "r2", "label", "Main " + topic + " Zone", "x", 35, "y", 50, "width", 26, "height", 18, "isCorrect", true),
                                                        Map.of("id", "r3", "label", "Supporting Structure", "x", 70, "y", 55, "width", 24, "height", 16, "isCorrect", false),
                                                        Map.of("id", "r4", "label", "Base Region", "x", 50, "y", 82, "width", 26, "height", 16, "isCorrect", false)
                                        );
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Click on the hotspot marker showing the Main %s Zone:", topic))
                                                        .instruction("Click the correct region marker on the diagram.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("HOTSPOT")
                                                        .icon("twemoji:magnifying-glass-tilted-right")
                                                        .imageUrl("https://images.unsplash.com/photo-1530595467537-0b5996c41f2d?auto=format&fit=crop&w=800&q=80")
                                                        .imageDescription("Educational diagram illustrating " + topic + " in " + subject)
                                                        .correctRegion("Main " + topic + " Zone")
                                                        .correctAnswer("Main " + topic + " Zone")
                                                        .regions(regions)
                                                        .correctFeedback("Spot on! You selected the Main " + topic + " Zone!")
                                                        .incorrectFeedback("Look closely at the labelled zones and select Main " + topic + " Zone.")
                                                        .build());
                                }
                                case TIMED_QUIZ -> {
                                        String coreAnswer = "Core Principle of " + topic + " (#" + i + ")";
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Fast Challenge: Which option best describes %s in %s?", topic, subject))
                                                        .instruction("Answer before the timer expires!")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("TIMED_QUIZ")
                                                        .timeLimitSeconds(20)
                                                        .icon("twemoji:stopwatch")
                                                        .correctAnswer(coreAnswer)
                                                        .options(List.of(
                                                                        OptionResponse.builder().optionText(coreAnswer).displayOrder(1).correct(true).build(),
                                                                        OptionResponse.builder().optionText("Secondary external factor").displayOrder(2).correct(false).build(),
                                                                        OptionResponse.builder().optionText("Opposing unrelated element").displayOrder(3).correct(false).build(),
                                                                        OptionResponse.builder().optionText("Irrelevant attribute").displayOrder(4).correct(false).build()))
                                                        .correctFeedback("Speedy and accurate!")
                                                        .incorrectFeedback("The correct answer was \"" + coreAnswer + "\".")
                                                        .build());
                                }
                                default -> {
                                        String coreAnswer = "Core Principle of " + topic + " (#" + i + ")";
                                        list.add(QuestionResponse.builder()
                                                        .questionText(String.format("Which of the following is a primary characteristic of %s in %s?", topic, subject))
                                                        .instruction("Choose one answer.")
                                                        .points(2)
                                                        .displayOrder(i)
                                                        .activityType("MCQ")
                                                        .icon("twemoji:question-mark")
                                                        .correctAnswer(coreAnswer)
                                                        .options(List.of(
                                                                        OptionResponse.builder().optionText(coreAnswer).displayOrder(1).correct(true).build(),
                                                                        OptionResponse.builder().optionText("Secondary external factor").displayOrder(2).correct(false).build(),
                                                                        OptionResponse.builder().optionText("Opposing unrelated element").displayOrder(3).correct(false).build(),
                                                                        OptionResponse.builder().optionText("Irrelevant attribute").displayOrder(4).correct(false).build()))
                                                        .correctFeedback("Great job! That is correct!")
                                                        .incorrectFeedback("The correct answer was \"" + coreAnswer + "\".")
                                                        .build());
                                }
                        }
                }
                return list;
        }

        @Override
        public ActivitySubmissionResponse submitActivity(
                        ActivitySubmissionRequest request) {

                StudentActivityAttempt attempt = StudentActivityAttempt.builder()
                                .studentId(1L)
                                .source(ActivitySource.AI)
                                .activityId(request.getActivityId())
                                .startedAt(LocalDateTime.now())
                                .submittedAt(LocalDateTime.now())
                                .status(AttemptStatus.SUBMITTED)
                                .build();

                List<AttemptQuestion> attemptQuestions = new ArrayList<>();

                if (request.getAnswers() != null && request.getGeneratedActivity() != null
                                && request.getGeneratedActivity().getQuestions() != null) {
                        for (AnswerRequest answer : request.getAnswers()) {

                                QuestionResponse question = request.getGeneratedActivity()
                                                .getQuestions()
                                                .stream()
                                                .filter(q -> q.getDisplayOrder() != null && q.getDisplayOrder()
                                                                .equals(answer.getQuestionNumber()))
                                                .findFirst()
                                                .orElse(null);

                                if (question == null) {
                                        continue;
                                }

                                AttemptQuestion attemptQuestion = AttemptQuestion.builder()
                                                .attempt(attempt)
                                                .questionType(
                                                                request.getGeneratedActivity()
                                                                                .getActivityType() != null
                                                                                                ? request.getGeneratedActivity().getActivityType().name()
                                                                                                : "MCQ")
                                                .questionText(question.getQuestionText())
                                                .instructions(question.getInstruction())
                                                .studentAnswer(answer.getStudentAnswer())
                                                .correctAnswer(question.getCorrectAnswer())
                                                .isCorrect(null)
                                                .feedback(null)
                                                .build();

                                attemptQuestions.add(attemptQuestion);
                        }
                }

                attempt.setQuestions(attemptQuestions);

                studentActivityAttemptRepository.save(attempt);

                return ActivitySubmissionResponse.builder()
                                .message("Activity submitted successfully.")
                                .analysisStatus("PENDING")
                                .activitySaved(true)
                                .build();
        }

        @Override
        public List<ActivityHistoryResponse> getActivityHistory(Long studentId) {

                List<StudentActivityAttempt> attempts = studentActivityAttemptRepository
                                .findByStudentIdOrderBySubmittedAtDesc(studentId);

                return attempts.stream()
                                .map(attempt -> ActivityHistoryResponse.builder()
                                                .attemptId(attempt.getAttemptId())
                                                .source(attempt.getSource())
                                                .activityId(attempt.getActivityId())
                                                .startedAt(attempt.getStartedAt())
                                                .submittedAt(attempt.getSubmittedAt())
                                                .status(attempt.getStatus())
                                                .build())
                                .toList();
        }

        @Override
        public ActivityHistoryDetailResponse getActivityHistoryById(
                        Long studentId,
                        Long attemptId) {

                StudentActivityAttempt attempt = studentActivityAttemptRepository
                                .findByAttemptIdAndStudentId(
                                                attemptId,
                                                studentId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Activity attempt not found"));

                List<AttemptQuestionResponse> questions = attempt.getQuestions()
                                .stream()
                                .map(question -> AttemptQuestionResponse.builder()
                                                .attemptQuestionId(
                                                                question.getAttemptQuestionId())
                                                .questionType(
                                                                question.getQuestionType())
                                                .questionText(
                                                                question.getQuestionText())
                                                .instructions(
                                                                question.getInstructions())
                                                .studentAnswer(
                                                                question.getStudentAnswer())
                                                .correctAnswer(
                                                                question.getCorrectAnswer())
                                                .isCorrect(
                                                                question.getIsCorrect())
                                                .feedback(
                                                                question.getFeedback())
                                                .build())
                                .toList();

                return ActivityHistoryDetailResponse.builder()
                                .attemptId(attempt.getAttemptId())
                                .source(attempt.getSource())
                                .activityId(attempt.getActivityId())
                                .startedAt(attempt.getStartedAt())
                                .submittedAt(attempt.getSubmittedAt())
                                .status(attempt.getStatus())
                                .questions(questions)
                                .build();
        }

        @Override
        @Transactional
        public ActivityCreateResponse createActivity(ActivityCreateRequest request) {

                Integer timeLimit = request.timeLimitMinutes();
                if (request.activityType() == ActivityType.TIMED_QUIZ && timeLimit == null) {
                        timeLimit = 10;
                }

                Activity activity = Activity.builder()
                                .moduleId(request.moduleId())
                                .gradeLevel(request.gradeLevel())
                                .displayOrder(request.displayOrder())
                                .title(request.title())
                                .description(request.description())
                                .activityType(request.activityType())
                                .difficulty(request.difficulty())
                                .timeLimitMinutes(timeLimit)
                                .totalMarks(0)
                                .status(request.status() != null ? request.status() : ActivityStatus.PUBLISHED)
                                .build();

                int totalMarks = 0;

                for (QuestionCreateRequest questionRequest : request.questions()) {

                        validateQuestionData(questionRequest, request.activityType());

                        Question question = Question.builder()
                                        .activity(activity)
                                        .questionText(questionRequest.questionText())
                                        .instructions(questionRequest.instructions())
                                        .imageUrl(questionRequest.imageUrl())
                                        .points(questionRequest.points())
                                        .displayOrder(questionRequest.displayOrder())
                                        .metadataJson(questionRequest.metadataJson())
                                        .build();

                        populateQuestionChildren(question, questionRequest);

                        activity.getQuestions().add(question);

                        totalMarks += questionRequest.points();
                }

                activity.setTotalMarks(totalMarks);

                Activity savedActivity = activityRepository.save(activity);

                evictActivityCaches(savedActivity.getModuleId(), savedActivity.getActivityId());

                return new ActivityCreateResponse(
                                savedActivity.getActivityId(),
                                "Activity created successfully");
        }

        private void populateQuestionChildren(Question question, QuestionCreateRequest questionRequest) {
                if (questionRequest.options() != null) {
                        for (OptionCreateRequest optionRequest : questionRequest.options()) {
                                Option option = Option.builder()
                                                .question(question)
                                                .optionText(optionRequest.optionText())
                                                .correct(optionRequest.correct())
                                                .displayOrder(optionRequest.displayOrder())
                                                .build();
                                question.getOptions().add(option);
                        }
                }

                if (questionRequest.fillBlanks() != null) {
                        for (FillBlankCreateRequest blankRequest : questionRequest.fillBlanks()) {
                                FillBlank fillBlank = FillBlank.builder()
                                                .question(question)
                                                .correctAnswer(blankRequest.correctAnswer())
                                                .blankIndex(blankRequest.blankIndex())
                                                .build();
                                question.getFillBlanks().add(fillBlank);
                        }
                }

                if (questionRequest.matchingPairs() != null) {
                        for (MatchingPairCreateRequest pairRequest : questionRequest.matchingPairs()) {
                                MatchingPair matchingPair = MatchingPair.builder()
                                                .question(question)
                                                .leftItem(pairRequest.leftItem())
                                                .rightItem(pairRequest.rightItem())
                                                .displayOrder(pairRequest.displayOrder())
                                                .build();
                                question.getMatchingPairs().add(matchingPair);
                        }
                }

                if (questionRequest.sortingItems() != null) {
                        for (SortingItemCreateRequest itemRequest : questionRequest.sortingItems()) {
                                SortingItem sortingItem = SortingItem.builder()
                                                .question(question)
                                                .itemText(itemRequest.itemText())
                                                .correctOrder(itemRequest.correctOrder())
                                                .build();
                                question.getSortingItems().add(sortingItem);
                        }
                }

                if (questionRequest.dragItems() != null) {
                        for (DragItemCreateRequest itemRequest : questionRequest.dragItems()) {
                                DragItem dragItem = DragItem.builder()
                                                .question(question)
                                                .itemText(itemRequest.itemText())
                                                .imageUrl(itemRequest.imageUrl())
                                                .build();
                                question.getDragItems().add(dragItem);
                        }
                }

                if (questionRequest.dropZones() != null) {
                        for (DropZoneCreateRequest zoneRequest : questionRequest.dropZones()) {
                                DropZone dropZone = DropZone.builder()
                                                .question(question)
                                                .zoneLabel(zoneRequest.zoneLabel())
                                                .build();
                                question.getDropZones().add(dropZone);
                        }
                }

                if (questionRequest.hotspotRegions() != null) {
                        for (HotspotRegionCreateRequest regionRequest : questionRequest.hotspotRegions()) {
                                HotspotRegion region = HotspotRegion.builder()
                                                .question(question)
                                                .xCoordinate(regionRequest.xCoordinate())
                                                .yCoordinate(regionRequest.yCoordinate())
                                                .width(regionRequest.width())
                                                .height(regionRequest.height())
                                                .build();
                                question.getHotspotRegions().add(region);
                        }
                }

                if (questionRequest.essay() != null) {
                        EssayCreateRequest essayRequest = questionRequest.essay();
                        Essay essay = Essay.builder()
                                        .question(question)
                                        .maxWordCount(essayRequest.maxWordCount())
                                        .minWordCount(essayRequest.minWordCount())
                                        .gradingRubric(essayRequest.gradingRubric())
                                        .build();
                        question.setEssay(essay);
                }

                createDragMappings(question, questionRequest);
        }

        @Override
        public List<ActivityListResponse> getAllActivities(Long moduleId) {
                String cacheKey = (moduleId != null) ? "activity:module:" + moduleId : "activity:all";
                String cached = redisCacheService.get(cacheKey);
                if (cached != null) {
                        try {
                                return objectMapper.readValue(cached, new com.fasterxml.jackson.core.type.TypeReference<List<ActivityListResponse>>() {});
                        } catch (Exception e) {
                                log.warn("Failed to deserialize cached activities for key {}: {}", cacheKey, e.getMessage());
                        }
                }

                List<Activity> activities = (moduleId != null)
                                ? activityRepository.findByModuleId(moduleId)
                                : activityRepository.findAll();

                List<ActivityListResponse> result = activities.stream()
                                .map(activity -> new ActivityListResponse(
                                                activity.getActivityId(),
                                                activity.getModuleId(),
                                                activity.getGradeLevel(),
                                                activity.getDisplayOrder(),
                                                activity.getTitle(),
                                                activity.getDescription(),
                                                activity.getActivityType(),
                                                activity.getDifficulty(),
                                                activity.getTimeLimitMinutes(),
                                                activity.getTotalMarks(),
                                                activity.getStatus()))
                                .toList();

                try {
                        redisCacheService.set(cacheKey, objectMapper.writeValueAsString(result), cacheTtlSeconds);
                } catch (Exception e) {
                        log.warn("Failed to serialize activities for cache key {}: {}", cacheKey, e.getMessage());
                }

                return result;
        }

        @Override
        public List<ActivityListResponse> getAllActivities() {
                return getAllActivities(null);
        }

        @Override
        @Transactional(readOnly = true)
        public ActivityDetailResponse getActivityById(Long activityId) {
                String cacheKey = "activity:id:" + activityId;
                String cached = redisCacheService.get(cacheKey);
                if (cached != null) {
                        try {
                                return objectMapper.readValue(cached, ActivityDetailResponse.class);
                        } catch (Exception e) {
                                log.warn("Failed to deserialize cached activity detail for key {}: {}", cacheKey, e.getMessage());
                        }
                }

                Activity activity = activityRepository.findById(activityId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Activity not found with id: " + activityId));

                List<QuestionDetailResponse> questions = activity.getQuestions().stream()
                                .map(question -> {

                                        List<OptionDetailResponse> options = question.getOptions().stream()
                                                        .map(option -> new OptionDetailResponse(
                                                                        option.getOptionId(),
                                                                        option.getOptionText(),
                                                                        option.getCorrect(),
                                                                        option.getDisplayOrder()))
                                                        .toList();

                                        List<FillBlankDetailResponse> fillBlanks = question.getFillBlanks().stream()
                                                        .map(blank -> new FillBlankDetailResponse(
                                                                        blank.getBlankId(),
                                                                        blank.getCorrectAnswer(),
                                                                        blank.getBlankIndex()))
                                                        .toList();

                                        List<MatchingPairDetailResponse> matchingPairs = question.getMatchingPairs()
                                                        .stream()
                                                        .map(pair -> new MatchingPairDetailResponse(
                                                                        pair.getPairId(),
                                                                        pair.getLeftItem(),
                                                                        pair.getRightItem(),
                                                                        pair.getDisplayOrder()))
                                                        .toList();

                                        List<SortingItemDetailResponse> sortingItems = question.getSortingItems()
                                                        .stream()
                                                        .map(item -> new SortingItemDetailResponse(
                                                                        item.getItemId(),
                                                                        item.getItemText(),
                                                                        item.getCorrectOrder()))
                                                        .toList();

                                        List<DragItemDetailResponse> dragItems = question.getDragItems().stream()
                                                        .map(item -> new DragItemDetailResponse(
                                                                        item.getItemId(),
                                                                        item.getItemText(),
                                                                        item.getImageUrl()))
                                                        .toList();

                                        List<DropZoneDetailResponse> dropZones = question.getDropZones().stream()
                                                        .map(zone -> new DropZoneDetailResponse(
                                                                        zone.getZoneId(),
                                                                        zone.getZoneLabel()))
                                                        .toList();

                                        List<DragMappingDetailResponse> dragMappings = question.getDragItems().stream()
                                                        .flatMap(item -> item.getDragMappings().stream())
                                                        .map(mapping -> new DragMappingDetailResponse(
                                                                        mapping.getMappingId(),
                                                                        mapping.getDragItem().getItemId(),
                                                                        mapping.getDropZone().getZoneId()))
                                                        .toList();

                                        List<HotspotRegionDetailResponse> hotspotRegions = question.getHotspotRegions()
                                                        .stream()
                                                        .map(region -> new HotspotRegionDetailResponse(
                                                                        region.getRegionId(),
                                                                        region.getXCoordinate(),
                                                                        region.getYCoordinate(),
                                                                        region.getWidth(),
                                                                        region.getHeight()))
                                                        .toList();

                                        EssayDetailResponse essay = null;

                                        if (question.getEssay() != null) {
                                                essay = new EssayDetailResponse(
                                                                question.getEssay().getEssayId(),
                                                                question.getEssay().getMaxWordCount(),
                                                                question.getEssay().getMinWordCount(),
                                                                question.getEssay().getGradingRubric());
                                        }

                                        return new QuestionDetailResponse(
                                                        question.getQuestionId(),
                                                        question.getQuestionText(),
                                                        question.getInstructions(),
                                                        question.getImageUrl(),
                                                        question.getPoints(),
                                                        question.getDisplayOrder(),
                                                        options,
                                                        fillBlanks,
                                                        matchingPairs,
                                                        sortingItems,
                                                        dragItems,
                                                        dropZones,
                                                        dragMappings,
                                                        hotspotRegions,
                                                        essay,
                                                        question.getMetadataJson());
                                })
                                .toList();

                ActivityDetailResponse response = new ActivityDetailResponse(
                                activity.getActivityId(),
                                activity.getModuleId(),
                                activity.getGradeLevel(),
                                activity.getDisplayOrder(),
                                activity.getTitle(),
                                activity.getDescription(),
                                activity.getActivityType(),
                                activity.getDifficulty(),
                                activity.getTimeLimitMinutes(),
                                activity.getTotalMarks(),
                                activity.getStatus(),
                                questions);

                try {
                        redisCacheService.set(cacheKey, objectMapper.writeValueAsString(response), cacheTtlSeconds);
                } catch (Exception e) {
                        log.warn("Failed to serialize activity detail for cache key {}: {}", cacheKey, e.getMessage());
                }

                return response;
        }

        @Override
        @Transactional
        public ActivityDetailResponse updateActivity(
                        Long activityId,
                        ActivityUpdateRequest request) {

                Activity activity = activityRepository.findById(activityId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Activity not found with id: " + activityId));

                activity.setModuleId(request.moduleId());
                activity.setGradeLevel(request.gradeLevel());
                activity.setDisplayOrder(request.displayOrder());
                activity.setTitle(request.title());
                activity.setDescription(request.description());
                activity.setActivityType(request.activityType());
                activity.setDifficulty(request.difficulty());
                activity.setTimeLimitMinutes(request.activityType() == ActivityType.TIMED_QUIZ && request.timeLimitMinutes() == null
                                ? 10
                                : request.timeLimitMinutes());
                activity.setStatus(request.status());

                activity.getQuestions().clear();

                int totalMarks = 0;

                for (QuestionCreateRequest questionRequest : request.questions()) {

                        validateQuestionData(
                                        questionRequest,
                                        request.activityType());

                        Question question = Question.builder()
                                        .activity(activity)
                                        .questionText(questionRequest.questionText())
                                        .instructions(questionRequest.instructions())
                                        .imageUrl(questionRequest.imageUrl())
                                        .points(questionRequest.points())
                                        .displayOrder(questionRequest.displayOrder())
                                        .metadataJson(questionRequest.metadataJson())
                                        .build();

                        populateQuestionChildren(question, questionRequest);

                        activity.getQuestions().add(question);

                        totalMarks += questionRequest.points();
                }

                activity.setTotalMarks(totalMarks);

                Activity savedActivity = activityRepository.save(activity);

                evictActivityCaches(savedActivity.getModuleId(), savedActivity.getActivityId());

                return getActivityById(savedActivity.getActivityId());
        }

        private void validateQuestionData(
                        QuestionCreateRequest request,
                        ActivityType activityType) {

                // If rich metadataJson is provided (e.g. from AI multi-agent generator), allow flexible saving
                if (request.metadataJson() != null && !request.metadataJson().isBlank()) {
                        return;
                }

                boolean hasOptions = request.options() != null &&
                                !request.options().isEmpty();

                boolean hasFillBlanks = request.fillBlanks() != null &&
                                !request.fillBlanks().isEmpty();

                boolean hasMatchingPairs = request.matchingPairs() != null &&
                                !request.matchingPairs().isEmpty();

                boolean hasSortingItems = request.sortingItems() != null &&
                                !request.sortingItems().isEmpty();

                boolean hasDragItems = request.dragItems() != null &&
                                !request.dragItems().isEmpty();

                boolean hasDropZones = request.dropZones() != null &&
                                !request.dropZones().isEmpty();

                boolean hasDragMappings = request.dragMappings() != null &&
                                !request.dragMappings().isEmpty();

                boolean hasHotspotRegions = request.hotspotRegions() != null &&
                                !request.hotspotRegions().isEmpty();

                boolean hasEssay = request.essay() != null;

                if (hasDragItems || hasDropZones || hasDragMappings) {
                        if (!hasDragItems || !hasDropZones || !hasDragMappings) {
                                throw new IllegalArgumentException(
                                                "DRAG_DROP requires dragItems, dropZones and dragMappings");
                        }
                        if (request.dragMappings().size() != request.dragItems().size()) {
                                throw new IllegalArgumentException(
                                                "Each drag item must have exactly one mapping");
                        }
                }

                int structureCount = 0;
                if (hasOptions) structureCount++;
                if (hasFillBlanks) structureCount++;
                if (hasMatchingPairs) structureCount++;
                if (hasSortingItems) structureCount++;
                if (hasDragItems) structureCount++;
                if (hasHotspotRegions) structureCount++;
                if (hasEssay) structureCount++;

                if (structureCount == 0) {
                        throw new IllegalArgumentException(
                                        "Question must contain at least one answer structure");
                }

                switch (activityType) {
                        case FILL_BLANK -> {
                                if (!hasFillBlanks) {
                                        throw new IllegalArgumentException("FILL_BLANK activity requires fillBlanks");
                                }
                        }
                        case MATCHING -> {
                                if (!hasMatchingPairs || request.matchingPairs().size() < 2) {
                                        throw new IllegalArgumentException("MATCHING requires at least two matchingPairs");
                                }
                        }
                        case SORTING -> {
                                if (!hasSortingItems || request.sortingItems().size() < 2) {
                                        throw new IllegalArgumentException("SORTING requires at least two sortingItems");
                                }
                                validateSortingItems(request);
                        }
                        case DRAG_DROP -> {
                                if (hasDragItems && hasDropZones && hasDragMappings) {
                                        validateDragMappings(request);
                                } else if (!hasSortingItems) {
                                        throw new IllegalArgumentException("DRAG_DROP activity requires dragItems/dropZones/dragMappings or sortingItems");
                                }
                        }
                        case HOTSPOT -> {
                                if (!hasHotspotRegions) {
                                        throw new IllegalArgumentException("HOTSPOT activity requires hotspotRegions");
                                }
                        }
                        case ESSAY, SHORT_ANSWER, CHALLENGE_QUIZ -> {
                                if (hasEssay) {
                                        validateEssay(request.essay());
                                } else if (!hasOptions && !hasFillBlanks) {
                                        throw new IllegalArgumentException(activityType + " requires essay, options, or fillBlanks data");
                                }
                        }
                        case TRUE_FALSE -> {
                                if (!hasOptions || request.options().size() != 2) {
                                        throw new IllegalArgumentException("TRUE_FALSE requires exactly two options");
                                }
                        }
                        case MCQ, QUIZ, TIMED_QUIZ -> {
                                if (!hasOptions || request.options().size() < 2) {
                                        throw new IllegalArgumentException(activityType + " requires at least two options");
                                }
                                long correctCount = request.options()
                                                .stream()
                                                .filter(OptionCreateRequest::correct)
                                                .count();
                                if (correctCount == 0) {
                                        throw new IllegalArgumentException(activityType + " requires at least one correct option");
                                }
                        }
                        case POLL -> {
                                if (!hasOptions || request.options().size() < 2) {
                                        throw new IllegalArgumentException("POLL requires at least two options");
                                }
                        }
                        case PROBLEM_SOLVING, APPLICATION_BASED -> {
                                if (!hasOptions && !hasEssay) {
                                        throw new IllegalArgumentException(activityType + " requires options or essay data");
                                }
                                if (hasEssay) {
                                        validateEssay(request.essay());
                                }
                        }
                }
        }

        private void validateSortingItems(QuestionCreateRequest request) {

                List<Integer> orders = request.sortingItems()
                                .stream()
                                .map(SortingItemCreateRequest::correctOrder)
                                .toList();

                int expectedSize = orders.size();

                for (int i = 1; i <= expectedSize; i++) {

                        if (!orders.contains(i)) {
                                throw new IllegalArgumentException(
                                                "SORTING correctOrder must contain every value from 1 to "
                                                                + expectedSize);
                        }
                }
        }

        private void validateDragMappings(QuestionCreateRequest request) {

                int dragItemCount = request.dragItems().size();
                int dropZoneCount = request.dropZones().size();

                java.util.Set<Integer> dragIndexes = new java.util.HashSet<>();
                java.util.Set<Integer> dropZoneIndexes = new java.util.HashSet<>();

                for (DragMappingCreateRequest mapping : request.dragMappings()) {

                        int dragIndex = mapping.dragItemIndex();
                        int dropZoneIndex = mapping.dropZoneIndex();

                        if (dragIndex < 1 || dragIndex > dragItemCount) {
                                throw new IllegalArgumentException(
                                                "Invalid drag item index: " + dragIndex);
                        }

                        if (dropZoneIndex < 1 || dropZoneIndex > dropZoneCount) {
                                throw new IllegalArgumentException(
                                                "Invalid drop zone index: " + dropZoneIndex);
                        }

                        if (!dragIndexes.add(dragIndex)) {
                                throw new IllegalArgumentException(
                                                "A drag item cannot have multiple mappings");
                        }

                        dropZoneIndexes.add(dropZoneIndex);
                }
        }

        private void validateEssay(EssayCreateRequest essay) {

                if (essay.minWordCount() != null &&
                                essay.maxWordCount() != null &&
                                essay.minWordCount() > essay.maxWordCount()) {

                        throw new IllegalArgumentException(
                                        "Minimum word count cannot be greater than maximum word count");
                }
        }

        @Override
        @Transactional
        public void deleteActivity(Long activityId) {

                Activity activity = activityRepository.findById(activityId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Activity not found with id: " + activityId));

                Long moduleId = activity.getModuleId();
                activityRepository.delete(activity);
                evictActivityCaches(moduleId, activityId);
        }

        private void evictActivityCaches(Long moduleId, Long activityId) {
                redisCacheService.delete("activity:all");
                if (activityId != null) {
                        redisCacheService.delete("activity:id:" + activityId);
                }
                if (moduleId != null) {
                        redisCacheService.delete("activity:module:" + moduleId);
                }
                redisCacheService.deletePattern("activity:module:*");
        }

}
