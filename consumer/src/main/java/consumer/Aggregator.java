package consumer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Aggregator {
    private final int topN;
    private final ConcurrentHashMap<String, Long> skierVertical = new ConcurrentHashMap<>();     // "resortID:day:skier" -> total
    private final ConcurrentHashMap<String, TopN> resortLeaderboards = new ConcurrentHashMap<>(); // "resort:day" -> top N
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();                  // eventIds already counted
    private final AtomicLong swipesProcessed = new AtomicLong();

    public Aggregator(int topN) {
        this.topN = topN;
    }

    public boolean IsProcessed(String eventId) {
        return processedEvents.contains(eventId);
    }

    public List<Map.Entry<String, Long>> Process(SwipeInfo s) {
        String skierKey = s.GetResortID() + ":" + s.GetDayID() + ":" + s.GetSkierID();
        long total = skierVertical.merge(skierKey, (long) s.GetVertical(), Long::sum);

        String boardKey = s.GetResortID() + ":" + s.GetDayID();
        TopN board = resortLeaderboards.computeIfAbsent(boardKey, k -> new TopN(topN));
        boolean changed = board.Update(s.GetSkierID(), total);

        processedEvents.add(s.GetEventId());
        swipesProcessed.incrementAndGet();
        return changed ? board.Snapshot() : null;
    }

    public long GetSwipesProcessed() {
        return swipesProcessed.get();
    }
}