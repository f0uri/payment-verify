package ma.paymentverify;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private EditText etServer, etAmount, etHolder, etNumber, etMonth, etYear, etCvv;
    private TextView tvStatus, tvResult, tvStep1, tvStep2, tvStep3;
    private Button btnVerify, btnCapture, btnCancel;
    private View postActions, serverBox;
    private Switch swOffline;
    private final LocalBank localBank = LocalBank.get();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String currentAuth = null;
    private SharedPreferences prefs;
    private View[] chips;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("pv", MODE_PRIVATE);

        etServer = findViewById(R.id.etServer);
        etAmount = findViewById(R.id.etAmount);
        etHolder = findViewById(R.id.etHolder);
        etNumber = findViewById(R.id.etNumber);
        etMonth = findViewById(R.id.etMonth);
        etYear = findViewById(R.id.etYear);
        etCvv = findViewById(R.id.etCvv);
        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        tvStep1 = findViewById(R.id.tvStep1);
        tvStep2 = findViewById(R.id.tvStep2);
        tvStep3 = findViewById(R.id.tvStep3);
        btnVerify = findViewById(R.id.btnVerify);
        btnCapture = findViewById(R.id.btnCapture);
        btnCancel = findViewById(R.id.btnCancel);
        postActions = findViewById(R.id.postActions);
        serverBox = findViewById(R.id.serverBox);
        swOffline = findViewById(R.id.swOffline);

        swOffline.setChecked(prefs.getBoolean("offline", true));
        swOffline.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("offline", on).apply();
            serverBox.setVisibility(on ? View.GONE : View.VISIBLE);
            checkServer();
        });
        serverBox.setVisibility(swOffline.isChecked() ? View.GONE : View.VISIBLE);

        etServer.setText(prefs.getString("server", getString(R.string.default_server)));

        // تنسيق رقم البطاقة 4-4-4-4 أثناء الكتابة
        etNumber.addTextChangedListener(new TextWatcher() {
            boolean editing;
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (editing) return;
                editing = true;
                String digits = s.toString().replaceAll("\\D", "");
                if (digits.length() > 19) digits = digits.substring(0, 19);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < digits.length(); i++) {
                    if (i > 0 && i % 4 == 0) sb.append(' ');
                    sb.append(digits.charAt(i));
                }
                s.replace(0, s.length(), sb.toString());
                editing = false;
            }
        });

        findViewById(R.id.btnTestOk).setOnClickListener(v -> fill("4242 4242 4242 4242"));
        findViewById(R.id.btnTestNoFunds).setOnClickListener(v -> fill("4000 0000 0000 9995"));
        findViewById(R.id.btnTestFake).setOnClickListener(v -> fill("4111 1111 1111 1111"));
        findViewById(R.id.btnTestBad).setOnClickListener(v -> fill("1234 5678 9012 3456"));

        // شرائح المبلغ السريعة (بأسلوب iOS)
        chips = new View[]{findViewById(R.id.btnChip50), findViewById(R.id.btnChip100), findViewById(R.id.btnChip200), findViewById(R.id.btnChip500)};
        for (View chip : chips) chip.setOnClickListener(v -> selectChip(v));
        selectChip(findViewById(R.id.btnChip100));

        btnVerify.setOnClickListener(v -> verify());
        btnCapture.setOnClickListener(v -> postAction(true));
        btnCancel.setOnClickListener(v -> postAction(false));

        findViewById(R.id.btnAdmin).setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(this, AdminActivity.class);
            i.putExtra("offline", offline());
            i.putExtra("server", etServer.getText().toString().trim());
            startActivity(i);
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        });

        checkServer();
    }

    private void fill(String number) {
        etNumber.setText(number);
        etMonth.setText("12");
        etYear.setText("29");
        etCvv.setText("123");
        if (etHolder.getText().length() == 0) etHolder.setText("TEST USER");
    }

    /** تحديد شريحة المبلغ: تُملأ وتتلألأ، وتظهر في حقل المبلغ */
    private void selectChip(View selected) {
        for (View chip : chips) {
            boolean on = chip == selected;
            chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            ((android.widget.TextView) chip).setTextColor(on ? Color.WHITE : Color.parseColor("#C7C7CC"));
        }
        String amt = ((android.widget.TextView) selected).getText().toString();
        etAmount.setText(amt);
    }

    /** ظهور ناعم (زجاجي) للنتائج */
    private void animateIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(28f);
        v.animate().alpha(1f).translationY(0f).setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private PaymentApi api() {
        String url = etServer.getText().toString().trim();
        prefs.edit().putString("server", url).apply();
        return new PaymentApi(url);
    }

    private boolean offline() { return swOffline.isChecked(); }

    private void checkServer() {
        if (offline()) { tvStatus.setText("وضع تجريبي داخل الهاتف ✅ (بنك محاكى، بدون خادم)"); return; }
        tvStatus.setText("جاري الاتصال بالخادم...");
        io.execute(() -> {
            try {
                JSONObject c = api().config();
                String gw = c.optString("gateway", "?");
                ui.post(() -> tvStatus.setText("متصل ✅  |  وضع: " + ("mock".equals(gw) ? "اختبار (محاكاة البنك)" : gw)));
            } catch (Exception e) {
                ui.post(() -> tvStatus.setText("تعذر الاتصال بالخادم ❌ — تحقق من العنوان"));
            }
        });
    }

    private void setSteps(int s1, int s2, int s3) {
        style(tvStep1, s1); style(tvStep2, s2); style(tvStep3, s3);
    }

    /** 0 = عادي، 1 = جارٍ، 2 = تم، 3 = خطأ */
    private void style(TextView tv, int state) {
        int color;
        switch (state) {
            case 1: color = Color.parseColor("#93C5FD"); break;
            case 2: color = Color.parseColor("#86EFAC"); break;
            case 3: color = Color.parseColor("#FCA5A5"); break;
            default: color = Color.parseColor("#94A3B8");
        }
        tv.setTextColor(color);
    }

    private void showResult(boolean ok, String text) {
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(text);
        tvResult.setBackgroundResource(ok ? R.drawable.bg_ok : R.drawable.bg_fail);
        tvResult.setTextColor(ok ? Color.parseColor("#BBF7D0") : Color.parseColor("#FECACA"));
    }

    private void restoreVerifyBtn() {
        btnVerify.setEnabled(true);
        btnVerify.setText("تحقّق من البطاقة واحجز المبلغ");
    }

    private void verify() {
        String number = etNumber.getText().toString();
        String mm = etMonth.getText().toString();
        String yy = etYear.getText().toString();
        String cvv = etCvv.getText().toString();
        String holder = etHolder.getText().toString();
        double amount;
        try { amount = Double.parseDouble(etAmount.getText().toString()); }
        catch (Exception e) { Toast.makeText(this, "أدخل مبلغاً صحيحاً", Toast.LENGTH_SHORT).show(); return; }

        postActions.setVisibility(View.GONE);
        currentAuth = null;

        // حالة تحميل ناعمة على الزر
        btnVerify.setEnabled(false);
        btnVerify.setText("⏳ جارٍ التحقق…");

        // المرحلة 1: التحقق الشكلي محلياً (بدون إنترنت)
        setSteps(1, 0, 0);
        CardValidator.Result v = CardValidator.validate(number, mm, yy, cvv, holder);
        if (!v.ok) {
            restoreVerifyBtn();
            setSteps(3, 0, 0);
            StringBuilder sb = new StringBuilder("❌ بيانات البطاقة غير صالحة\n");
            for (String e : v.errors) sb.append("• ").append(e).append('\n');
            showResult(false, sb.toString().trim());
            return;
        }

        // المرحلة 2: الحجز لدى البنك عبر الخادم
        setSteps(2, 1, 0);
        showResult(true, "⏳ جارٍ التحقق لدى البنك...");
        io.execute(() -> {
            try {
                JSONObject r = offline() ? localBank.verify(amount, number, cvv, "local") : api().verify(amount, number, mm, yy, cvv, holder);
                ui.post(() -> {
                    btnVerify.setEnabled(true);
                    if (r.optBoolean("ok")) {
                        setSteps(2, 2, 1);
                        currentAuth = r.optString("authorizationId");
                        JSONObject card = r.optJSONObject("card");
                        String txt = "✅ " + r.optString("message")
                                + "\nالبطاقة: " + (card != null ? card.optString("brand").toUpperCase() + " •••• " + card.optString("last4") : "")
                                + "\nالمبلغ المحجوز: " + r.optDouble("amount") + " " + r.optString("currency")
                                + "\nرقم الحجز: " + currentAuth;
                        if (r.has("remainingBalance") && !r.isNull("remainingBalance"))
                            txt += "\n(ديمو) الرصيد المتبقي: " + r.optDouble("remainingBalance") + " MAD";
                        showResult(true, txt);
                        postActions.setVisibility(View.VISIBLE);
                    } else {
                        String stage = r.optString("stage");
                        if ("bank".equals(stage)) setSteps(2, 3, 0); else setSteps(3, 0, 0);
                        StringBuilder sb = new StringBuilder("❌ ").append(r.optString("message"));
                        JSONArray errs = r.optJSONArray("errors");
                        if (errs != null) for (int i = 0; i < errs.length(); i++) sb.append("\n• ").append(errs.optString(i));
                        if (r.has("code")) sb.append("\nرمز الخطأ: ").append(r.optString("code"));
                        showResult(false, sb.toString());
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    restoreVerifyBtn();
                    setSteps(2, 3, 0);
                    showResult(false, "❌ تعذر الاتصال بالخادم\n" + e.getMessage());
                });
            }
        });
    }

    private void postAction(boolean capture) {
        if (currentAuth == null) return;
        btnCapture.setEnabled(false); btnCancel.setEnabled(false);
        io.execute(() -> {
            try {
                JSONObject r = offline()
                        ? (capture ? localBank.capture(currentAuth) : localBank.cancel(currentAuth))
                        : (capture ? api().capture(currentAuth) : api().cancel(currentAuth));
                ui.post(() -> {
                    btnCapture.setEnabled(true); btnCancel.setEnabled(true);
                    boolean ok = r.optBoolean("ok");
                    String txt = (ok ? (capture ? "✅ " : "↩ ") : "❌ ") + r.optString("message");
                    if (ok && capture) txt += "\nالمبلغ المسحوب: " + r.optDouble("amount") + " " + r.optString("currency");
                    showResult(ok, txt);
                    if (ok) {
                        setSteps(2, 2, capture ? 2 : 0);
                        postActions.setVisibility(View.GONE);
                        currentAuth = null;
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    btnCapture.setEnabled(true); btnCancel.setEnabled(true);
                    showResult(false, "❌ تعذر الاتصال بالخادم\n" + e.getMessage());
                });
            }
        });
    }
}
