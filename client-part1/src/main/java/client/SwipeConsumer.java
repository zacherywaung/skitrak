package client;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;

public class SwipeConsumer implements Runnable{
    private final BlockingQueue<SwipeInfo> bq;
    private final CloseableHttpClient client;
    private final String baseUrl;
    private final int total;
    private int success = 0;
    private int fail = 0;

    public SwipeConsumer(BlockingQueue<SwipeInfo> bq, CloseableHttpClient client, String baseUrl, int total) {
        this.bq = bq;
        this.client = client;
        this.baseUrl = baseUrl;
        this.total = total;
    }

    @Override
    public void run() {
        for(int i = 0; i < total; i++) {
            try {
                SwipeInfo swipe = bq.take();
                HttpPost req = toRequest(swipe);
//              HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                int statusCode = client.execute(req, response -> response.getCode());
                if(statusCode == 201) {
                    success++;
                }else {
                    fail++;
                }
            }catch(IOException e) {
                System.out.println("Send fail: " +  e.getMessage());
                fail++;
            }catch(InterruptedException e){
                System.out.println("Interrupted!!!");
                return;
            }
        }
    }

    public int GetSuccess() {
        return success;
    }
    public int GetFail() {
        return fail;
    }

    // POST {baseurl}/skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID} HTTP/1.1
    // headers
    // \r\n
    // {"time": 217,"liftID": 21}
    private HttpPost toRequest(SwipeInfo swipe) {
        String url = baseUrl + "/skiers/" + swipe.getResortID() +
                "/seasons/" + swipe.getSeasonID() +
                "/days/" + swipe.getDayID() +
                "/skiers/" + swipe.getSkierID();
        String body = "{\"time\": " + swipe.getTime() + ",\"liftID\": " + swipe.getLiftID() + "}";
        HttpPost req = new HttpPost(url);
        req.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return req;
    }
}
