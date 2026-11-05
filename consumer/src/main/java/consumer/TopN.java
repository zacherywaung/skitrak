package consumer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TopN {
    private final int n;    // n skiers
    private final Map<String, Long> top = new HashMap<>();   // skierID -> vertical

    public TopN(int n) {
        this.n = n;
    }

    private static int CompareRank(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
        int byVertical = Long.compare(b.getValue(), a.getValue());
        if (byVertical != 0) {
            return byVertical;
        }
        return Long.compare(Long.parseLong(a.getKey()), Long.parseLong(b.getKey()));
    }

    // update skier total vertical, returns true if the list changed
    public synchronized boolean Update(String skierID, long total) {
        // already on the list, or list not full yet: update
        if (top.containsKey(skierID) || top.size() < n) {
            top.put(skierID, total);
            return true;
        }
        // find last place, compare
        Map.Entry<String, Long> last = Collections.max(top.entrySet(), TopN::CompareRank);
        if (CompareRank(Map.entry(skierID, total), last) < 0) {
            top.remove(last.getKey());
            top.put(skierID, total);
            return true;
        }
        return false;
    }

    // sorted copy
    public synchronized List<Map.Entry<String, Long>> Snapshot() {
        List<Map.Entry<String, Long>> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : top.entrySet()) {
            list.add(Map.entry(e.getKey(), e.getValue()));
        }
        list.sort(TopN::CompareRank);
        return list;
    }
}
