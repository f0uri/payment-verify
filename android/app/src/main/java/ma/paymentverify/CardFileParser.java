package ma.paymentverify;

import java.util.ArrayList;
import java.util.List;

/**
 * تحليل ملف نصي (CSV/TXT) يحتوي بطاقات لفحصها جماعياً من لوحة الأدمن.
 * كل سطر: number, expMonth, expYear, cvv, holderName?
 * الفواصل المقبولة: , ; | Tab — سطر الترويسة يُتخطى تلقائياً، والاسم اختياري.
 */
public final class CardFileParser {
    public static final int MAX_CARDS = 200;

    public static final class Item {
        public int line;
        public String number, expMonth, expYear, cvv, holder;
        public boolean bad;
        public String error;
    }

    private CardFileParser() {}

    public static List<Item> parse(String text) {
        List<Item> out = new ArrayList<>();
        if (text == null) return out;
        boolean firstNonEmpty = true;
        boolean limitNotified = false;
        int cardCount = 0;

        String[] lines = text.replace("\uFEFF", "").split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            if (cardCount >= MAX_CARDS) {
                if (!limitNotified) {
                    Item it = new Item();
                    it.line = i + 1; it.bad = true;
                    it.error = "تم تجاهل باقي الملف — الحد الأقصى " + MAX_CARDS + " بطاقة في المرة الواحدة";
                    out.add(it);
                    limitNotified = true;
                }
                continue;
            }

            String[] f = line.split("[;,\\t|]");
            for (int j = 0; j < f.length; j++) f[j] = f[j].trim();

            // تخطي سطر الترويسة (أول سطر غير فارغ يبدأ بحروف)
            if (firstNonEmpty && f.length > 1 && f[0].matches(".*[A-Za-z\u0600-\u06FF].*")) {
                firstNonEmpty = false;
                continue;
            }
            firstNonEmpty = false;

            Item it = new Item();
            it.line = i + 1;
            if (f.length < 4) {
                it.bad = true;
                it.error = "صيغة غير صالحة — كل سطر: الرقم، الشهر، السنة، CVV، الاسم (اختياري)";
                out.add(it);
                continue;
            }
            it.number = f[0]; it.expMonth = f[1]; it.expYear = f[2]; it.cvv = f[3];
            it.holder = (f.length > 4 && !f[4].isEmpty()) ? f[4] : "بطاقة من ملف";
            cardCount++;
            out.add(it);
        }
        return out;
    }
}
