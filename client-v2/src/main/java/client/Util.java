package client;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
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

    static void writeCsv(List<SwipeToCsv> records, String filepath) throws IOException {
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(filepath))) {
            writer.write("timestamp,requestType,latency,statusCode,resortID");
            writer.newLine();
            for(SwipeToCsv e : records) {
                writer.write(e.GetStartTime() + ",SWIPE," + e.GetLatency() + ","
                        + e.GetStatusCode() + "," + e.GetResortID());
                writer.newLine();
            }
        }
    }

    static void wirteThroughputToCsv(int[] interval10, String filepath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filepath))) {
            writer.write("Time/Sec, Throughput");
            writer.newLine();
            for(int i = 0; i < interval10.length; i++) {
                writer.write((i * 10) + "," + (interval10[i] / 10.0));
                writer.newLine();
            }
        }
    }
}
