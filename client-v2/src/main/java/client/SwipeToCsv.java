package client;

public class SwipeToCsv {
    private final long startTime;
    private final double latency;
    private final int statusCode;
    private final int resortID;
    private final int skierID;
    private final int liftID;

    SwipeToCsv(long startTime, double latency, int statusCode, int resortID, int skierID, int liftID) {
        this.startTime = startTime;
        this.latency = latency;
        this.statusCode = statusCode;
        this.resortID = resortID;
        this.skierID = skierID;
        this.liftID = liftID;
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

    public int GetSkierID() {
        return skierID;
    }

    public int GetLiftID() {
        return liftID;
    }
}
