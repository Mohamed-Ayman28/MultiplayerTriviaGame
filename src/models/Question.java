package models;

import java.util.List;

public class Question {

    private int questionId;
    private String text;
    private String category;
    private String difficultyLevel;
    private List<String> choices;
    private String correctAnswer; // A,B,C,D,etc

    public Question() {
    }

    public Question(int questionId, String text, String category,
                    String difficultyLevel, List<String> choices, String correctAnswer) {
        this.questionId = questionId;
        this.text = text;
        this.category = category;
        this.difficultyLevel = difficultyLevel;
        this.choices = choices;
        this.correctAnswer = correctAnswer;
    }

    public int getQuestionId() {
        return questionId;
    }

    public void setQuestionId(int questionId) {
        this.questionId = questionId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDifficultyLevel() {
        return difficultyLevel;
    }

    public void setDifficultyLevel(String difficultyLevel) {
        this.difficultyLevel = difficultyLevel;
    }

    public List<String> getChoices() {
        return choices;
    }

    public void setChoices(List<String> choices) {
        this.choices = choices;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }
}