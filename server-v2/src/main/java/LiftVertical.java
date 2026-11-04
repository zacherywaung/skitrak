import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class LiftVertical {
    private static final int nums = 40;
    private static final int[] vertical = new int[nums + 1];
    private static final String filename = "lift_vertical.json";

    private static class LiftFile {
        Map<String, Integer> lifts;
    }

    // upload json file to memory
    static {
        try (InputStream in = LiftVertical.class.getClassLoader().getResourceAsStream(filename)) {
            if (in == null) {
                throw new IllegalStateException(filename + " NOT FOUND!!!");
            }
            LiftFile file = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), LiftFile.class);
            for(int i = 1; i < nums; i++) {
                Integer v = file.lifts.get(String.valueOf(i));
                vertical[i] = v;
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int GetVerticalByID(int liftID) {
        return vertical[liftID];
    }
}
