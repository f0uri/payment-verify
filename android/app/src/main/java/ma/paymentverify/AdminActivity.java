package ma.paymentverify;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * لوحة الأدمن: التحكم في عدد المحاولات، مدة الحظر، حدود المبلغ،
 * وعرض/رفع الحظر عن العملاء.
 * - وضع تجريبي: يتحكم في LocalBank داخل الهاتف مباشرة.
 * - وضع الخادم: يستدعي /api/admin/* بمفتاح ADMIN_KEY.
 */
public class AdminActivity extends Activity {

    private boolean offline;
    private String server;
    private String adminKey;
    private SharedPreferences prefs;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LocalBank bank = LocalBank.get();

    private EditText etMax, etBlock, etMin, etMaxAmt, etBlockKey, etKey;
    private TextView tvStats, tvMsg, tvMode, tvCardResults;
    private LinearLayout attemptsList, loginBox, panel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_admin);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        prefs = getSharedPreferences("pv", MODE_PRIVATE);
        offline = getIntent().getBooleanExtra("offline", true);
        server = getIntent().getStringExtra("server");
        adminKey = prefs.getString("adminKey", null);

        etMax = findViewById(R.id.etMax); etBlock = findViewById(R.id.etBlock);
        etMin = findViewById(R.id.etMin); etMaxAmt = findViewById(R.id.etMaxAmt);
        etBlockKey = findViewById(R.id.etBlockKey); etKey = findViewById(R.id.etKey);
        tvStats = findViewById(R.id.tvStats); tvMsg = findViewById(R.id.tvMsg); tvMode = findViewById(R.id.tvMode);
        attemptsList = findViewById(R.id.attemptsList); loginBox = findViewById(R.id.loginBox); panel = findViewById(R.id.panel);

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnBlock).setOnClickListener(v -> blockManual());
        findViewById(R.id.btnResetAll).setOnClickListener(v -> resetAll());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refresh());
        findViewById(R.id.btnLogin).setOnClickListener(v -> login());
        findViewById(R.id.btnUpload).setOnClickListener(v -> pickCardFile());
        tvCardResults = findViewById(R.id.tvCardResults);

        if (offline) {
            tvMode.setText("وضع تجريبي داخل الهاتف — التغييرات تُطبَّق على البنك المحاكى");
            refresh();
        } else {
            tvMode.setText("متصل بالخادم: " + server);
            if (adminKey == null) { loginBox.setVisibility(View.VISIBLE); panel.setVisibility(View.GONE); }
            else refresh();
        }
    }

    private PaymentApi api() { return new PaymentApi(server).withAdminKey(adminKey); }

    private void msg(boolean ok, String t) {
        tvMsg.setVisibility(View.VISIBLE); tvMsg.setText(t);
        tvMsg.setBackgroundResource(ok ? R.drawable.bg_ok : R.drawable.bg_fail);
        tvMsg.setTextColor(ok ? Color.parseColor("#BBF7D0") : Color.parseColor("#FECACA"));
    }

    // ---------------- الدخول (وضع الخادم) ----------------
    private void login() {
        String key = etKey.getText().toString().trim();
        io.execute(() -> {
            try {
                JSONObject r = new PaymentApi(server).adminLogin(key);
                ui.post(() -> {
                    if (r.optBoolean("ok")) {
                        adminKey = key; prefs.edit().putString("adminKey", key).apply();
                        loginBox.setVisibility(View.GONE); panel.setVisibility(View.VISIBLE); refresh();
                    } else { panel.setVisibility(View.VISIBLE); msg(false, "❌ " + r.optString("message")); }
                });
            } catch (Exception e) { ui.post(() -> { panel.setVisibility(View.VISIBLE); msg(false, "❌ تعذر الاتصال بالخادم\n" + e.getMessage()); }); }
        });
    }

    // ---------------- تحميل البيانات ----------------
    private void refresh() {
        if (offline) {
            etMax.setText(String.valueOf(bank.maxFailedAttempts));
            etBlock.setText(String.valueOf(bank.blockMinutes));
            etMin.setText(String.valueOf((long) bank.minAmount));
            etMaxAmt.setText(String.valueOf((long) bank.maxAmount));
            try { renderAttempts(bank.listAttempts()); } catch (Exception ignored) {}
            int blocked = 0;
            try { for (JSONObject a : bank.listAttempts()) if (a.optBoolean("blocked")) blocked++; } catch (Exception ignored) {}
            tvStats.setText("📊 محظورون الآن: " + blocked + "\nعدد المحاولات المسموح: " + bank.maxFailedAttempts + "  |  مدة الحظر: " + bank.blockMinutes + " دقيقة");
            return;
        }
        io.execute(() -> {
            try {
                JSONObject s = api().adminSettings().getJSONObject("settings");
                JSONObject st = api().adminStats().getJSONObject("stats");
                JSONArray arr = api().adminAttempts().getJSONArray("attempts");
                List<JSONObject> list = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
                ui.post(() -> {
                    etMax.setText(String.valueOf(s.optInt("maxFailedAttempts")));
                    etBlock.setText(String.valueOf(s.optInt("blockMinutes")));
                    etMin.setText(String.valueOf(s.optDouble("minAmount")));
                    etMaxAmt.setText(String.valueOf(s.optDouble("maxAmount")));
                    tvStats.setText("📊 إجمالي العمليات: " + st.optInt("total")
                            + "  |  حجوزات ناجحة: " + st.optInt("authorized")
                            + "\nرفض من البنك: " + st.optInt("declinedByBank")
                            + "  |  رفض شكلي (وهمية): " + st.optInt("rejectedByValidation")
                            + "\nمسحوبة: " + st.optInt("captured") + "  |  ملغاة: " + st.optInt("canceled")
                            + "  |  محظورون الآن: " + st.optInt("blockedNow"));
                    renderAttempts(list);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if ("unauthorized".equals(e.getMessage()) || (e.getMessage() != null && e.getMessage().contains("401"))) {
                        adminKey = null; prefs.edit().remove("adminKey").apply();
                        loginBox.setVisibility(View.VISIBLE); panel.setVisibility(View.GONE);
                    } else msg(false, "❌ تعذر الاتصال بالخادم\n" + e.getMessage());
                });
            }
        });
    }

    private void renderAttempts(List<JSONObject> list) {
        attemptsList.removeAllViews();
        if (list.isEmpty()) {
            TextView t = new TextView(this); t.setText("لا يوجد عملاء لديهم محاولات فاشلة حالياً");
            t.setTextColor(Color.parseColor("#94A3B8")); t.setPadding(0, 12, 0, 12); attemptsList.addView(t); return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (JSONObject a : list) {
            boolean blocked = a.optBoolean("blocked");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_card); row.setPadding(24, 20, 24, 20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 12; row.setLayoutParams(lp);

            TextView info = new TextView(this);
            String until = "";
            Object bu = a.opt("blockedUntil");
            if (blocked && bu != null) {
                long ms = bu instanceof Number ? ((Number) bu).longValue() : parseIso(String.valueOf(bu));
                if (ms > 0) until = "  حتى " + fmt.format(new Date(ms));
            }
            info.setText(a.optString("key") + "\n" + (blocked ? "🔴 محظور" + until : "🟡 محاولات فاشلة: " + a.optInt("failedCount")));
            info.setTextColor(Color.parseColor("#E2E8F0")); info.setTextSize(13);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(info);

            Button btn = new Button(this);
            btn.setText(blocked ? "رفع الحظر" : "حظر");
            btn.setTextColor(Color.WHITE); btn.setTextSize(12); btn.setAllCaps(false);
            btn.setBackgroundResource(blocked ? R.drawable.bg_btn_secondary : R.drawable.bg_btn_danger);
            btn.setOnClickListener(v -> { if (blocked) unblock(a.optString("key")); else block(a.optString("key"), null); });
            row.addView(btn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 90));
            attemptsList.addView(row);
        }
    }

    private long parseIso(String iso) {
        try { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US) { { setTimeZone(java.util.TimeZone.getTimeZone("UTC")); } }.parse(iso).getTime(); }
        catch (Exception e) { return 0; }
    }

    // ---------------- الإجراءات ----------------
    private void save() {
        try {
            int max = Integer.parseInt(etMax.getText().toString().trim());
            int blk = Integer.parseInt(etBlock.getText().toString().trim());
            double min = Double.parseDouble(etMin.getText().toString().trim());
            double maxA = Double.parseDouble(etMaxAmt.getText().toString().trim());
            if (max < 1 || max > 100) { msg(false, "❌ عدد المحاولات يجب أن يكون بين 1 و 100"); return; }
            if (blk < 1) { msg(false, "❌ مدة الحظر يجب أن تكون دقيقة على الأقل"); return; }
            if (min >= maxA) { msg(false, "❌ الحد الأدنى يجب أن يكون أقل من الحد الأعلى"); return; }
            if (offline) {
                bank.maxFailedAttempts = max; bank.blockMinutes = blk; bank.minAmount = min; bank.maxAmount = maxA;
                msg(true, "✅ تم حفظ الإعدادات — تُطبَّق فوراً"); refresh(); return;
            }
            JSONObject body = new JSONObject().put("maxFailedAttempts", max).put("blockMinutes", blk).put("minAmount", min).put("maxAmount", maxA);
            io.execute(() -> {
                try {
                    JSONObject r = api().adminSaveSettings(body);
                    ui.post(() -> { msg(r.optBoolean("ok"), r.optBoolean("ok") ? "✅ تم حفظ الإعدادات على الخادم" : "❌ " + r.optJSONArray("errors")); refresh(); });
                } catch (Exception e) { ui.post(() -> msg(false, "❌ " + e.getMessage())); }
            });
        } catch (NumberFormatException e) { msg(false, "❌ أدخل أرقاماً صحيحة في كل الحقول"); }
        catch (Exception e) { msg(false, "❌ " + e.getMessage()); }
    }

    private void blockManual() {
        String key = etBlockKey.getText().toString().trim();
        if (key.isEmpty()) { msg(false, "❌ أدخل المعرّف أو IP"); return; }
        block(key, null); etBlockKey.setText("");
    }

    private void block(String key, Integer minutes) {
        if (offline) { bank.block(key, minutes != null ? minutes : bank.blockMinutes); msg(true, "✅ تم حظر " + key); refresh(); return; }
        io.execute(() -> { try { api().adminBlock(key, minutes); ui.post(() -> { msg(true, "✅ تم حظر " + key); refresh(); }); } catch (Exception e) { ui.post(() -> msg(false, "❌ " + e.getMessage())); } });
    }

    private void unblock(String key) {
        if (offline) { bank.unblock(key); msg(true, "✅ تم رفع الحظر عن " + key); refresh(); return; }
        io.execute(() -> { try { api().adminUnblock(key); ui.post(() -> { msg(true, "✅ تم رفع الحظر عن " + key); refresh(); }); } catch (Exception e) { ui.post(() -> msg(false, "❌ " + e.getMessage())); } });
    }

    private void resetAll() {
        if (offline) { int n = bank.resetAll(); msg(true, "✅ تم تصفير " + n + " سجل"); refresh(); return; }
        io.execute(() -> { try { JSONObject r = api().adminResetAll(); ui.post(() -> { msg(true, "✅ تم تصفير " + r.optInt("cleared") + " سجل"); refresh(); }); } catch (Exception e) { ui.post(() -> msg(false, "❌ " + e.getMessage())); } });
    }

    // ---------------- فحص ملف بطاقات ----------------
    private static final int FILE_REQUEST = 42;

    private void pickCardFile() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(android.content.Intent.createChooser(i, "اختر ملف البطاقات"), FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        msg(true, "⏳ جارٍ قراءة الملف وفحص البطاقات...");
        io.execute(() -> {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                final String content = bos.toString("UTF-8");
                final String text = offline ? batchCheckOffline(content) : formatServerCards(api().adminCheckCards(content));
                ui.post(() -> {
                    tvCardResults.setVisibility(View.VISIBLE);
                    tvCardResults.setText(text);
                    msg(true, "✅ اكتمل فحص الملف");
                });
            } catch (Exception e) {
                ui.post(() -> msg(false, "❌ تعذر فحص الملف: " + e.getMessage()));
            }
        });
    }

    /** فحص محلي (وضع تجريبي): تحقق شكلي + البنك المحاكى، بدون تسجيل محاولات */
    private String batchCheckOffline(String content) throws Exception {
        java.util.List<CardFileParser.Item> items = CardFileParser.parse(content);
        if (items.isEmpty()) return "الملف فارغ أو لا يحتوي بطاقات";
        int total = 0, valid = 0;
        StringBuilder sb = new StringBuilder();
        for (CardFileParser.Item it : items) {
            String status, detail;
            if (it.bad) { status = "bad_line"; detail = it.error; }
            else {
                CardValidator.Result v = CardValidator.validate(it.number, it.expMonth, it.expYear, it.cvv, it.holder);
                if (!v.ok) { status = "invalid"; detail = v.errors.isEmpty() ? "" : v.errors.get(0); }
                else {
                    JSONObject c = bank.checkCard(it.number);
                    status = c.optString("code");
                    detail = c.optString("message");
                    if (c.optBoolean("ok")) valid++;
                }
            }
            total++;
            sb.append("• [").append(it.line).append("] ").append(mask(it)).append(" → ").append(statusLabel(status)).append('\n');
            if (!detail.isEmpty()) sb.append("   ").append(detail).append('\n');
        }
        sb.insert(0, "الإجمالي: " + total + "  |  تعمل: " + valid + "  |  لا تعمل: " + (total - valid) + "\n\n");
        return sb.toString();
    }

    /** تنسيق نتيجة الخادم (وضع الخادم) */
    private String formatServerCards(JSONObject r) throws Exception {
        if (!r.optBoolean("ok")) return "❌ " + r.optString("message");
        JSONObject s = r.getJSONObject("summary");
        StringBuilder sb = new StringBuilder("الإجمالي: ").append(s.optInt("total"))
                .append("  |  تعمل: ").append(s.optInt("valid"))
                .append("  |  لا تعمل: ").append(s.optInt("total") - s.optInt("valid")).append("\n\n");
        org.json.JSONArray arr = r.getJSONArray("results");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.getJSONObject(i);
            String card = x.has("last4") ? "•••• " + x.optString("last4") : (x.has("number") ? x.optString("number") : "سطر " + x.optInt("line"));
            sb.append("• [").append(x.optInt("line")).append("] ").append(card)
                    .append(" → ").append(statusLabel(x.optString("status"))).append('\n');
        }
        return sb.toString();
    }

    private static String mask(CardFileParser.Item it) {
        if (it.bad || it.number == null) return "—";
        String d = it.number.replaceAll("[\\s-]", "");
        return d.length() >= 4 ? "•••• " + d.substring(d.length() - 4) : d;
    }

    private static String statusLabel(String s) {
        switch (s) {
            case "valid": return "✅ تعمل";
            case "invalid": return "❌ بيانات غير صالحة";
            case "insufficient_funds": return "⚠️ رصيد غير كافٍ";
            case "generic_decline": return "⛔ مرفوضة من البنك";
            case "expired_card": return "⛔ منتهية الصلاحية";
            case "incorrect_cvc": return "⛔ CVV خاطئ";
            case "fraudulent": return "⛔ مشتبه بها (احتيال)";
            case "card_not_found": return "⛔ غير معروفة لدى البنك";
            case "bad_line": return "✳️ سطر غير مفهوم";
            default: return "⚠️ " + s;
        }
    }
}
