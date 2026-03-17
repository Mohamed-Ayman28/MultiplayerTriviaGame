package models;

import java.util.Date;

public class ScoreEntry {

    private User user;
    private int score;
    private Date date;
    private String gametype;
    private int totalQuestions;   
    private int correctAnswers;   

    public ScoreEntry(User user, int score, Date date, String gametype, int totalQuestions, int correctAnswers) {
        this.user = user;
        this.score = score;
        this.date = date;
        this.gametype = gametype;
        this.totalQuestions = totalQuestions;
        this.correctAnswers = correctAnswers;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }

    public String getGametype() {
        return gametype;
    }

    public void setGametype(String gametype) {
        this.gametype = gametype;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(int totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public int getCorrectAnswers() {
        return correctAnswers;
    }

    public void setCorrectAnswers(int correctAnswers) {
        this.correctAnswers = correctAnswers;
    }
}
