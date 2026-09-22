package ma.paymentverify;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * عميل بسيط للاتصال بخادم payment-verify (Express).
 * كل الدوال متزامنة (blocking) — يجب استدعاؤها من خيط خلفي (انظر MainActivity).
 */
public class PaymentApi {

    private final String baseUrl;
    private String adminKey = null;

    public PaymentApi(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public PaymentApi withAdminKey(String key) { this.adminKey = key; return this; }

    // ---- الأدمن ----
    public JSONObject adminLogin(String key) throws Exception { return request("POST", "/api/admin/login", new JSONObject().put("key", key)); }
    public JSONObject adminSettings() throws Exception { return request("GET", "/api/admin/settings", null); }
    public JSONObject adminSaveSettings(JSONObject settings) throws Exception { return request("PUT", "/api/admin/settings", settings); }
    public JSONObject adminAttempts() throws Exception { return request("GET", "/api/admin/attempts", null); }
    public JSONObject adminStats() throws Exception { return request("GET", "/api/admin/stats", null); }
    public JSONObject adminUnblock(String key) throws Exception { return request("DELETE", "/api/admin/attempts/" + java.net.URLEncoder.encode(key, "UTF-8"), null); }
    public JSONObject adminBlock(String key, Integer minutes) throws Exception {
        JSONObject b = new JSONObject(); if (minutes != null) b.put("minutes", minutes);
        return request("POST", "/api/admin/attempts/" + java.net.URLEncoder.encode(key, "UTF-8") + "/block", b);
    }
    public JSONObject adminResetAll() throws Exception { return request("DELETE", "/api/admin/attempts", null); }
    public JSONObject adminCheckCards(String content) throws Exception { return request("POST", "/api/admin/cards/check", new JSONObject().put("content", content)); }

    public JSONObject config() throws Exception {
        return request("GET", "/api/config", null);
    }

    /** التحقق من البطاقة وحجز المبلغ */
    public JSONObject verify(double amount, String number, String mm, String yy, String cvv, String holder) throws Exception {
        JSONObject card = new JSONObject()
                .put("number", number)
                .put("expMonth", mm)
                .put("expYear", yy)
                .put("cvv", cvv)
                .put("holderName", holder);
        JSONObject body = new JSONObject().put("amount", amount).put("card", card);
        return request("POST", "/api/payments/verify", body);
    }

    /** سحب المبلغ بعد إتمام الشحن */
    public JSONObject capture(String authorizationId) throws Exception {
        return request("POST", "/api/payments/capture", new JSONObject().put("authorizationId", authorizationId));
    }

    /** إلغاء الحجز */
    public JSONObject cancel(String authorizationId) throws Exception {
        return request("POST", "/api/payments/cancel", new JSONObject().put("authorizationId", authorizationId));
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-User-Id", "android-" + android.os.Build.MODEL.replace(' ', '_'));
        if (adminKey != null) conn.setRequestProperty("X-Admin-Key", adminKey);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        JSONObject json = new JSONObject(sb.toString());
        json.put("_httpStatus", code);
        return json;
    }
}
