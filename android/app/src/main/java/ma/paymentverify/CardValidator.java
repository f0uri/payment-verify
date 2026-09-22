package ma.paymentverify;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;

/**
 * نفس منطق src/cardValidator.js في الخادم، منقول إلى Java.
 * المستوى الأول من الحماية: يرفض البطاقات العشوائية/الوهمية قبل الاتصال بالخادم.
 */
public final class CardValidator {

    public static final class Result {
        public final boolean ok;
        public final List<String> errors;
        public final String brand;
        public final String last4;

        Result(boolean ok, List<String> errors, String brand, String last4) {
            this.ok = ok; this.errors = errors; this.brand = brand; this.last4 = last4;
        }
    }

    private static final Object[][] BRANDS = {
        {"visa",       Pattern.compile("^4\\d{12}(\\d{3})?(\\d{3})?$")},
        {"mastercard", Pattern.compile("^(5[1-5]\\d{14}|2(2[2-9]\\d|[3-6]\\d{2}|7[01]\\d|720)\\d{12})$")},
        {"amex",       Pattern.compile("^3[47]\\d{13}$")},
        {"discover",   Pattern.compile("^6(?:011|5\\d{2})\\d{12}$")},
        {"cmi",        Pattern.compile("^6(?:04|27)\\d{13}$")},
    };

    private CardValidator() {}

    public static String normalize(String number) {
        return number == null ? "" : number.replaceAll("[\\s-]", "");
    }

    /** خوارزمية Luhn */
    public static boolean luhnCheck(String number) {
        String d = normalize(number);
        if (!d.matches("^\\d{12,19}$")) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (dbl) { n *= 2; if (n > 9) n -= 9; }
            sum += n;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    public static String detectBrand(String number) {
        String d = normalize(number);
        for (Object[] b : BRANDS) if (((Pattern) b[1]).matcher(d).matches()) return (String) b[0];
        return "unknown";
    }

    public static Result validate(String number, String expMonth, String expYear, String cvv, String holder) {
        List<String> errors = new ArrayList<>();
        String digits = normalize(number);

        if (digits.isEmpty()) errors.add("رقم البطاقة مطلوب");
        else if (!digits.matches("^\\d{12,19}$")) errors.add("رقم البطاقة يجب أن يتكون من 12 إلى 19 رقماً");
        else if (!luhnCheck(digits)) errors.add("رقم البطاقة غير صحيح (فشل في فحص Luhn) — قد يكون رقماً وهمياً");

        String brand = detectBrand(digits);
        if (!digits.isEmpty() && "unknown".equals(brand)) errors.add("نوع البطاقة غير معروف أو غير مدعوم");

        // تاريخ الانتهاء
        try {
            int m = Integer.parseInt(expMonth.trim());
            int y = Integer.parseInt(expYear.trim());
            if (y < 100) y += 2000;
            Calendar now = Calendar.getInstance();
            if (m < 1 || m > 12) errors.add("الشهر يجب أن يكون بين 01 و 12");
            else {
                Calendar exp = Calendar.getInstance();
                exp.set(y, m - 1, 1);
                exp.set(Calendar.DAY_OF_MONTH, exp.getActualMaximum(Calendar.DAY_OF_MONTH));
                if (exp.before(now)) errors.add("البطاقة منتهية الصلاحية");
                else if (y > now.get(Calendar.YEAR) + 20) errors.add("تاريخ الانتهاء بعيد جداً وغير منطقي");
            }
        } catch (Exception e) {
            errors.add("تاريخ الانتهاء غير صالح");
        }

        int cvvLen = "amex".equals(brand) ? 4 : 3;
        String c = cvv == null ? "" : cvv.trim();
        if (!c.matches("^\\d+$")) errors.add("رمز CVV يجب أن يكون أرقاماً فقط");
        else if (c.length() != cvvLen) errors.add("رمز CVV يجب أن يتكون من " + cvvLen + " أرقام");

        if (holder == null || holder.trim().length() < 3) errors.add("اسم حامل البطاقة مطلوب");

        String last4 = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
        return new Result(errors.isEmpty(), errors, brand, last4);
    }
}
