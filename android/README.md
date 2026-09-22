# 📱 Payment Verify — تطبيق Android (APK)

تطبيق Android أصلي (Java) يتصل بخادم `payment-verify` ويطبّق نفس مراحل التحقق الثلاث:
1. **تحقق شكلي محلي** على الهاتف (`CardValidator.java` — Luhn، نوع البطاقة، الانتهاء، CVV) بدون إنترنت.
2. **حجز المبلغ لدى البنك** عبر `POST /api/payments/verify`.
3. **سحب / إلغاء** بعد إتمام الشحن.

## التثبيت على هاتفك (بدون حاسوب)
1. حمّل `payment-verify.apk` وثبّته (فعّل "تثبيت من مصادر غير معروفة").
2. افتح التطبيق — **الوضع التجريبي داخل الهاتف** مفعّل افتراضياً: بنك محاكى (`LocalBank.java`) يعمل بدون خادم أو إنترنت، جرّب البطاقات التجريبية مباشرة.
3. عندما يصبح لديك خادم حقيقي، أوقف الوضع التجريبي من المفتاح وضع **عنوان الخادم**:
   - محاكي Android: `http://10.0.2.2:3000`
   - هاتف حقيقي على نفس الشبكة Wi-Fi: `http://<IP-حاسوبك>:3000` (مثال `http://192.168.1.10:3000`)
   - خادم على الإنترنت: `https://your-server.com`

> التطبيق يسمح بـ HTTP للتطوير فقط (`res/xml/network_security_config.xml`). في الإنتاج استخدم HTTPS واحذف هذا الملف.

## إعادة البناء
### بدون Android Studio (سكربت سريع)
```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk   # يحتاج build-tools;34.0.0 و platforms;android-34
./scripts/build-apk.sh
```
### مع Android Studio
افتح مجلد `android/` كمشروع Gradle → Build → Build APK.

## بنية الملفات
```
android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/ma/paymentverify/
│   │   ├── MainActivity.java     # الواجهة والتدفق
│   │   ├── CardValidator.java    # التحقق الشكلي (نسخة Java من cardValidator.js)
│   │   ├── LocalBank.java        # بنك محاكى داخل الهاتف للوضع التجريبي
│   │   ├── AdminActivity.java    # لوحة الأدمن (عدد المحاولات، الحظر، الإحصائيات، فحص ملف بطاقات)
│   │   ├── CardFileParser.java   # تحليل ملف CSV/TXT للبطاقات (فواصل , ; | Tab)
│   │   └── PaymentApi.java       # عميل HTTP للخادم
│   └── res/                      # التخطيط، الأنماط، الأيقونة
├── scripts/build-apk.sh          # بناء APK بدون Gradle
├── build.gradle / app/build.gradle / settings.gradle   # لـ Android Studio
└── payment-verify.apk            # الناتج (موقّع بمفتاح debug)
```

## ملاحظات للإنتاج
- **لا ترسل رقم البطاقة إلى خادمك** — استخدم SDK البوابة (Stripe Android SDK / صفحة CMI المستضافة) للحصول على token وأرسله بدلاً من الرقم. `PaymentApi.verify()` جاهز للتعديل ليرسل `paymentMethodId`.
- وقّع التطبيق بمفتاح release خاص بك قبل النشر على Google Play.
- ثبّت شهادة SSL (certificate pinning) إذا كان التطبيق مالياً.

## البناء السحابي بدون حاسوب
ارفع مجلد `android` (أو ملف البذرة `android-sources.tar.gz.b64`) مع `.github/workflows/android.yml` إلى GitHub —
سيُبنى APK موقّع تلقائياً في تبويب Actions. راجع `GITHUB-UPLOAD.md` في جذر المشروع.
