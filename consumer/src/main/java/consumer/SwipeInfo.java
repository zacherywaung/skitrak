package consumer;


//Queue message format:
//
//        {
//        "eventId": "UUID",
//        "resortID": "string",
//        "dayID": "string",
//        "skierID": "string",
//        "liftID": "string",
//        "vertical": 200,
//        "time": 217,
//        "timestamp": "ISO-8601",
//        "serverId": "string"
//        }
public class SwipeInfo {
    private final String eventId;
    private final String resortID;
    private final String dayID;
    private final String skierID;
    private final String liftID;
    private final int vertical;
    private final int time;
    private final String timestamp;
    private final String serverId;

    public SwipeInfo(String eventId, String resortID, String dayID, String skierID, String liftID,
                     int vertical, int time, String timestamp, String serverId)
    {
        this.eventId = eventId;
        this.resortID = resortID;
        this.dayID = dayID;
        this.skierID = skierID;
        this.liftID = liftID;
        this.vertical = vertical;
        this.time = time;
        this.timestamp = timestamp;
        this.serverId = serverId;
    }

    public String GetEventId() {
        return eventId;
    }
    public String GetResortID() {
        return resortID;
    }
    public String GetDayID() {
        return dayID;
    }
    public String GetSkierID() {
        return skierID;
    }
    public int GetVertical() {
        return vertical;
    }
    public String GetTimestamp() {
        return timestamp;
    }
}