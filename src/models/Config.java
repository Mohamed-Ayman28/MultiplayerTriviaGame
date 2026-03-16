package models;

import java.util.List;

public class Config {
    private int minPlayers;
    private int maxPlayers;
    private int questionTime;               
    private List<Integer> warningTimes;    
    private int defaultQuestionCount;
    private int maxTeams;
    private String lookupHost;
    private int lookupPort;

    public Config() {
    }
    
    public Config(int minPlayers, int maxPlayers, int questionTime,
                  List<Integer> warningTimes, int defaultQuestionCount, int maxTeams) {
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.questionTime = questionTime;
        this.warningTimes = warningTimes;
        this.defaultQuestionCount = defaultQuestionCount;
        this.maxTeams = maxTeams;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public void setMinPlayers(int minPlayers) {
        this.minPlayers = minPlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public int getQuestionTime() {
        return questionTime;
    }

    public void setQuestionTime(int questionTime) {
        this.questionTime = questionTime;
    }

    public List<Integer> getWarningTimes() {
        return warningTimes;
    }

    public void setWarningTimes(List<Integer> warningTimes) {
        this.warningTimes = warningTimes;
    }

    public int getDefaultQuestionCount() {
        return defaultQuestionCount;
    }

    public void setDefaultQuestionCount(int defaultQuestionCount) {
        this.defaultQuestionCount = defaultQuestionCount;
    }

    public int getMaxTeams() {
        return maxTeams;
    }

    public void setMaxTeams(int maxTeams) {
        this.maxTeams = maxTeams;
    }

    public String getLookupHost() {
        return lookupHost;
    }

    public void setLookupHost(String lookupHost) {
        this.lookupHost = lookupHost;
    }

    public int getLookupPort() {
        return lookupPort;
    }

    public void setLookupPort(int lookupPort) {
        this.lookupPort = lookupPort;
    }
}
