package client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Util {
    private static double mean(List<Double> l) {
        double ret = 0;
        for(double e : l) {
            ret += e;
        }
        return ret / l.size();
    }

    private static double calPercent(List<Double> l, double percent) throws Exception {
        int index = (int)Math.ceil((percent / 100.0 * l.size())) - 1;
        return l.get(index);
    }

    public static String summary(List<Double> l) throws Exception {
        List<Double> ordered = new ArrayList<>(l);
        Collections.sort(ordered);
        return String.format("mean = %.2f, median = %.2f, P95 = %.2f, P99 = %.2f, min = %.2f, max = %.2f",
                mean(ordered),
                calPercent(ordered, 50),
                calPercent(ordered, 95),
                calPercent(ordered, 99),
                ordered.get(0),
                ordered.get(ordered.size() - 1)
        );
    }
}
