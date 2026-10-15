package client;

public class SwipeToCsv {
    private final long startTime;
    private final double latency;
    private final int statusCode;
    private final int resortID;

    SwipeToCsv(long startTime, double latency, int statusCode, int resortID) {
        this.startTime = startTime;
        this.latency = latency;
        this.statusCode = statusCode;
        this.resortID = resortID;
    }

    public long GetStartTime() {
        return startTime;
    }

    public double GetLatency() {
        return latency;
    }

    public int GetStatusCode() {
        return statusCode;
    }

    public int GetResortID() {
        return resortID;
    }
}
