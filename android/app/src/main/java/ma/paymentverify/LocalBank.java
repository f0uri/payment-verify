package ma.paymentverify;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "بنك محلي" داخل التطبيق — نسخة Java من src/gateways/mockGateway.js + paymentService.js
 * يسمح بتجربة التطبيق كاملاً على الهاتف بدون أي خادم أو إنترنت.
 * في الإنتاج يُستبدل بالاتصال بالخادم (PaymentApi) الذي يتواصل مع البنك الحقيقي.
 */
public class LocalBank {

    private static final class Card { double balance; String brand; String decline;
        Card(double b, String br, String d) { balance = b; brand = br; decline = d; } }

    private final Map<String, Card> cards = new HashMap<>();
    private final Map<String, double[]> holds = new HashMap<>(); // authId -> {amount, status(0=auth,1=captured,2=canceled)}
    private final Map<String, String> holdCard = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();

    // إعدادات يتحكم فيها الأدمن
    public int maxFailedAttempts = 5;
    public int blockMinutes = 15;
    public double minAmount = 5;
    public double maxAmount = 20000;

    /** قائمة العملاء (محاولات فاشلة / محظورون) */
    public java.util.List<JSONObject> listAttempts() throws Exception {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>(failures.keySet());
        keys.addAll(blockedUntil.keySet());
        long now = System.currentTimeMillis();
        for (String k : keys) {
            Long b = blockedUntil.get(k);
            boolean blocked = b != null && b > now;
            out.add(new JSONObject().put("key", k).put("failedCount", failures.getOrDefault(k, 0))
                    .put("blocked", blocked).put("blockedUntil", blocked ? b : 0));
        }
        return out;
    }
    public void unblock(String key) { failures.remove(key); blockedUntil.remove(key); }
    public void block(String key, int minutes) { failures.remove(key); blockedUntil.put(key, System.currentTimeMillis() + minutes * 60_000L); }
    public int resetAll() { int n = failures.size() + blockedUntil.size(); failures.clear(); blockedUntil.clear(); return n; }

    private static LocalBank instance;
    public static synchronized LocalBank get() { if (instance == null) instance = new LocalBank(); return instance; }

    private LocalBank() {
        cards.put("4242424242424242", new Card(1000, "visa", null));
        cards.put("5555555555554444", new Card(250, "mastercard", null));
        cards.put("4000000000009995", new Card(0, "visa", "insufficient_funds"));
        cards.put("4000000000000002", new Card(1000, "visa", "generic_decline"));
        cards.put("4000000000000069", new Card(1000, "visa", "expired_card"));
        cards.put("4000000000000127", new Card(1000, "visa", "incorrect_cvc"));
        cards.put("4000000000000341", new Card(1000, "visa", "fraudulent"));
    }

    private static String msg(String code) {
        switch (code) {
            case "insufficient_funds": return "الرصيد غير كافٍ في البطاقة";
            case "generic_decline": return "رفض البنك العملية";
            case "expired_card": return "البطاقة منتهية الصلاحية";
            case "incorrect_cvc": return "رمز الأمان CVV غير صحيح";
            case "fraudulent": return "تم رفض البطاقة للاشتباه في عملية احتيال";
            case "card_not_found": return "البطاقة غير معروفة لدى البنك — قد تكون بطاقة وهمية";
            default: return "خطأ أثناء معالجة العملية";
        }
    }

    private JSONObject declined(String code, String key) throws Exception {
        int f = failures.getOrDefault(key, 0) + 1;
        if (f >= maxFailedAttempts) { blockedUntil.put(key, System.currentTimeMillis() + blockMinutes * 60_000L); f = 0; }
        failures.put(key, f);
        return new JSONObject().put("ok", false).put("stage", "bank").put("status", "declined").put("code", code).put("message", msg(code));
    }

    /** الحجز المسبق (Pre-Authorization) */
    public JSONObject verify(double amount, String number, String cvv, String key) throws Exception {
        Long b = blockedUntil.get(key);
        if (b != null && b > System.currentTimeMillis()) {
            long mins = (b - System.currentTimeMillis()) / 60_000 + 1;
            return new JSONObject().put("ok", false).put("stage", "rate_limit").put("message", "تم حظر المحاولات مؤقتاً بسبب كثرة الأخطاء. حاول بعد " + mins + " دقيقة");
        }
        if (amount < minAmount || amount > maxAmount)
            return new JSONObject().put("ok", false).put("stage", "amount").put("message", "المبلغ يجب أن يكون بين " + (long) minAmount + " و " + (long) maxAmount + " MAD");

        String n = CardValidator.normalize(number);
        Card c = cards.get(n);
        if (c == null) return declined("card_not_found", key);
        if (c.decline != null) return declined(c.decline, key);
        if (c.balance < amount) return declined("insufficient_funds", key);

        try { Thread.sleep(500); } catch (InterruptedException ignored) {} // محاكاة زمن استجابة البنك
        c.balance -= amount;
        String id = "auth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        holds.put(id, new double[]{amount, 0});
        holdCard.put(id, n);
        failures.remove(key);

        return new JSONObject().put("ok", true).put("stage", "bank")
                .put("message", "البطاقة صالحة والرصيد كافٍ — تم حجز المبلغ")
                .put("authorizationId", id).put("amount", amount).put("currency", "MAD")
                .put("card", new JSONObject().put("brand", c.brand).put("last4", n.substring(n.length() - 4)))
                .put("remainingBalance", c.balance);
    }

    /** فحص بطاقة (للأدمن) — بدون تسجيل محاولات فاشلة ولا حجز دائم */
    public JSONObject checkCard(String number) throws Exception {
        String n = CardValidator.normalize(number);
        Card c = cards.get(n);
        if (c == null) return new JSONObject().put("ok", false).put("code", "card_not_found").put("message", msg("card_not_found"));
        if (c.decline != null) return new JSONObject().put("ok", false).put("code", c.decline).put("message", msg(c.decline));
        if (c.balance < Math.max(1, minAmount)) return new JSONObject().put("ok", false).put("code", "insufficient_funds").put("message", msg("insufficient_funds"));
        return new JSONObject().put("ok", true).put("code", "valid").put("message", "البطاقة تعمل — الرصيد كافٍ")
                .put("brand", c.brand).put("last4", n.substring(n.length() - 4));
    }

    public JSONObject capture(String authId) throws Exception {
        double[] h = holds.get(authId);
        if (h == null) return new JSONObject().put("ok", false).put("message", "الحجز غير موجود");
        if (h[1] != 0) return new JSONObject().put("ok", false).put("message", "الحجز لم يعد نشطاً");
        h[1] = 1;
        return new JSONObject().put("ok", true).put("message", "تم سحب المبلغ بنجاح").put("amount", h[0]).put("currency", "MAD");
    }

    public JSONObject cancel(String authId) throws Exception {
        double[] h = holds.get(authId);
        if (h == null) return new JSONObject().put("ok", false).put("message", "الحجز غير موجود");
        if (h[1] != 0) return new JSONObject().put("ok", false).put("message", "الحجز لم يعد نشطاً");
        h[1] = 2;
        cards.get(holdCard.get(authId)).balance += h[0];
        return new JSONObject().put("ok", true).put("message", "تم إلغاء الحجز وإرجاع المبلغ");
    }
}
