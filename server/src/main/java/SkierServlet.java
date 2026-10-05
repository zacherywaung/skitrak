import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import model.LiftRide;
import model.ResponseMsg;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;
import java.util.logging.Level;


public class SkierServlet extends HttpServlet {
    private Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(SkierServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        // Expected URL: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}

        logger.info("GET request received: " + req.getPathInfo());

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String urlPath = req.getPathInfo();

        // Check we have a URL
        if (urlPath == null || urlPath.isEmpty()) {
            logger.warning("GET missing parameters");
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
            return;
        }

        String[] urlParts = urlPath.split("/");

        if (!isUrlValid(urlParts)) {
            logger.warning("Invalid URL: " + req.getPathInfo());
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
        } else {
            logger.info("Valid GET request processed for: " + req.getPathInfo());
            res.setStatus(HttpServletResponse.SC_OK);

            // Extract parameters
            String resortID = urlParts[1];
            String seasonID = urlParts[3];
            String dayID = urlParts[5];
            String skierID = urlParts[7];

            // Create response
            ResponseMsg response = new ResponseMsg(
                    String.format("GET request processed for skier %s at resort %s, season %s, day %s",
                            skierID, resortID, seasonID, dayID)
            );

            res.getWriter().write(gson.toJson(response));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        // REST Endpoint: POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
//        {
//            "time": 217,
//            "liftID": 21
//        }
//        Validation (return 400 on failure):
//        resortID, seasonID, dayID, skierID in the URL path must be positive integers
//        time must be between 1 and 360 (minutes into the ski day)
//        liftID must be between 1 and 40
//        On a valid swipe: return 201 Created. That's it — no aggregation, no per-skier state, no leaderboard update. The server simply accepts the swipe as fast as it can. (Real aggregation arrives in Assignment 2.)

        logger.info("POST request received: " + req.getPathInfo());

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String urlPath = req.getPathInfo();

        // Check we have a URL
        if (urlPath == null || urlPath.isEmpty()) {
            logger.warning("POST missing parameters");
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
            return;
        }

        String[] urlParts = urlPath.split("/");

        if (!isUrlValid(urlParts)) {
            logger.warning("Invalid url: " + req.getPathInfo());
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
            return;
        }

        // Parse JSON body
        try {
            StringBuilder jsonBody = new StringBuilder();
            BufferedReader reader = req.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBody.append(line);
            }

            LiftRide liftRide = gson.fromJson(jsonBody.toString(), LiftRide.class);

            if (liftRide == null || !isLiftRideValid(liftRide)) {
                logger.warning("Invalid lift ride: " + jsonBody.toString());
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
                return;
            }

            // Extract URL parameters
            String resortID = urlParts[1];
            String seasonID = urlParts[3];
            String dayID = urlParts[5];
            String skierID = urlParts[7];

            // Process the lift ride (in real app, save to database)
            logger.info("Lift ride recorded success");
            res.setStatus(HttpServletResponse.SC_CREATED);
//            ResponseMsg response = new ResponseMsg(
//                    String.format("Lift ride recorded: skier %s, resort %s, season %s, day %s, time %d, lift %d",
//                            skierID, resortID, seasonID, dayID, liftRide.getTime(), liftRide.getLiftID())
//            );
//
//            res.getWriter().write(gson.toJson(response));

        } catch (JsonSyntaxException e) {
            logger.warning("JSON syntax error: " + e.getMessage());
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON format")));
        }
    }

    private boolean isUrlValid(String[] urlParts) {
        // Expected URL: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
        // urlParts = ["", "1", "seasons", "2019", "days", "1", "skiers", "123"]

        if (urlParts.length != 8) {
            return false;
        }

        try {
            // Check structure
            if (!urlParts[2].equals("seasons") ||
                    !urlParts[4].equals("days") ||
                    !urlParts[6].equals("skiers")) {
                return false;
            }

            // Validate numeric parameters && resortID seasonID dayID skierID > 0
            return Integer.parseInt(urlParts[1]) > 0            // resortID
            && Integer.parseInt(urlParts[3]) > 0                // seasonID
            && Integer.parseInt(urlParts[5]) > 0                // dayID
            && Integer.parseInt(urlParts[7]) > 0;               // skierID

        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isLiftRideValid(LiftRide liftRide) {
        // Basic validation
        return liftRide.getTime() >= 1 &&
                liftRide.getTime() <= 360 &&    // Max 6 hours (360 minutes)
                liftRide.getLiftID() >= 1 &&
                liftRide.getLiftID() <= 40;     // 1 <= liftID <= 40
    }
}