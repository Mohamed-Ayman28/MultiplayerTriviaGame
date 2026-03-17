package models;

import java.util.List;

public class Config {
    private int minPlayers;
    private int maxPlayers;
    private int questionTime;               
    private List<Integer> warningTimes;    
    private int defaultQuestionCount;
    private String lookupHost;
    private int lookupPort;

    public Config() {
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
