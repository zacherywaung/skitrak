import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/health")
public class HealthChecker extends HttpServlet {
    private static final String serverId = System.getenv().getOrDefault("SERVER_ID", "local");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        if (RabbitMQManager.IsConnected()) {
            res.setStatus(HttpServletResponse.SC_OK);
            res.getWriter().write("{\"status\":\"ok\",\"serverId\":\"" + serverId + "\"}");
        } else {
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            res.getWriter().write("{\"status\":\"rabbitmq disconnected\",\"serverId\":\"" + serverId + "\"}");
        }
    }
}