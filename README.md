# 💳 Payment Verify — التحقق من بطاقة الدفع قبل الشحن

مشروع Node.js/Express جاهز للتطوير، يتحقق من أن بطاقة الدفع **حقيقية** و**فيها رصيد كافٍ** قبل تنفيذ الشحن، ويمنع البطاقات الوهمية.

## الفكرة الأساسية (مهم جداً)

> ❗ لا توجد أي طريقة قانونية لقراءة رصيد بطاقة العميل مباشرة. الطريقة الوحيدة الموثوقة هي **الحجز المسبق (Pre-Authorization)**: تطلب من البنك حجز المبلغ بدون سحبه، فإذا وافق البنك فالبطاقة حقيقية والرصيد كافٍ، وإذا رفض تعرف السبب بالضبط (رصيد غير كافٍ، بطاقة وهمية، منتهية...).

المشروع يطبق 3 طبقات حماية:

| الطبقة | ماذا تفعل | ماذا تمنع |
|---|---|---|
| **1. التحقق الشكلي** (`src/cardValidator.js`) | خوارزمية Luhn + نوع البطاقة + تاريخ الانتهاء + CVV | الأرقام العشوائية المكتوبة يدوياً |
| **2. الحجز لدى البنك** (`src/gateways/`) | Pre-Authorization عبر بوابة الدفع | البطاقات الوهمية التي تمر بـ Luhn، البطاقات بدون رصيد، المسروقة |
| **3. الحماية من التخمين** (`src/paymentService.js`) | حظر بعد 5 محاولات فاشلة لمدة 15 دقيقة + حدود للمبلغ | هجمات Card Testing |

### دورة العملية
```
العميل يُدخل البطاقة
    │
    ▼
[1] تحقق شكلي  ──✗──▶ رفض فوري + رسالة الخطأ (بدون الاتصال بالبنك)
    │ ✓
    ▼
[2] POST /api/payments/verify ─▶ البنك يحجز المبلغ (authorize)
    │ ✗ insufficient_funds / card_not_found / fraudulent ...
    │ ✓ authorizationId
    ▼
    تنفيذ الشحن
    │
    ├─ نجح  ──▶ POST /api/payments/capture  (سحب المبلغ الفعلي، قد يكون أقل من المحجوز)
    └─ فشل  ──▶ POST /api/payments/cancel   (إرجاع المبلغ للعميل)
```

## التشغيل

```bash
npm install
cp .env.example .env     # الوضع الافتراضي mock (لا يحتاج أي حساب)
npm start                # http://localhost:3000
npm test                 # تشغيل الاختبارات
```

## بطاقات الاختبار (وضع mock)

| الرقم | النتيجة |
|---|---|
| 4242 4242 4242 4242 | ✅ سليمة، رصيد 1000 MAD |
| 5555 5555 5555 4444 | ✅ Mastercard، رصيد 250 MAD |
| 4000 0000 0000 9995 | ❌ رصيد غير كافٍ |
| 4000 0000 0000 0002 | ❌ رفض من البنك |
| 4000 0000 0000 0069 | ❌ منتهية الصلاحية |
| 4000 0000 0000 0127 | ❌ CVV خاطئ |
| 4000 0000 0000 0341 | ❌ مشتبه بها (احتيال) |
| أي رقم آخر | ❌ بطاقة غير معروفة لدى البنك (وهمية) |

## API

| Method | Endpoint | Body | الوصف |
|---|---|---|---|
| GET | `/api/config` | — | الوضع الحالي وبطاقات الاختبار |
| POST | `/api/payments/verify` | `{ amount, card:{number,expMonth,expYear,cvv,holderName} }` أو `{ amount, paymentMethodId }` | تحقق + حجز المبلغ |
| POST | `/api/payments/capture` | `{ authorizationId, amount? }` | سحب المبلغ بعد الشحن |
| POST | `/api/payments/cancel` | `{ authorizationId }` | إلغاء الحجز |
| GET | `/api/payments` | — | سجل العمليات |

### لوحة الأدمن — `/admin.html`
محمية بمفتاح `ADMIN_KEY` من `.env` (يُرسل في هيدر `X-Admin-Key`). **غيّر المفتاح الافتراضي `admin1234` قبل النشر.**

| Method | Endpoint | الوصف |
|---|---|---|
| POST | `/api/admin/login` | `{ key }` التحقق من المفتاح |
| GET / PUT | `/api/admin/settings` | `maxFailedAttempts`, `blockMinutes`, `minAmount`, `maxAmount` — تُطبَّق فوراً |
| GET | `/api/admin/attempts` | العملاء ذوو المحاولات الفاشلة والمحظورون |
| DELETE | `/api/admin/attempts/:key` | رفع الحظر وتصفير المحاولات لعميل |
| POST | `/api/admin/attempts/:key/block` | `{ minutes? }` حظر يدوي |
| DELETE | `/api/admin/attempts` | تصفير كل المحاولات |
| POST | `/api/admin/cards/check` | `{ content }` فحص جماعي لملف بطاقات (CSV/TXT) |
| GET | `/api/admin/stats` | إحصائيات |

**فحص ملف بطاقات:** من اللوحة (قسم «📄 فحص ملف بطاقات») يرفع الأدمن ملف CSV/TXT — كل سطر:
`number, expMonth, expYear, cvv, holderName?` (الفواصل المقبولة: `,` `;` Tab `|`، والترويسة تُتخطى تلقائياً، والحد الأقصى 200 بطاقة).
كل بطاقة تُفحص بتحقق شكلي ثم **حجز تجريبي فوري يُلغى لحظياً** — بدون أي خصم، وبدون احتسابها محاولات فاشلة أو إظهارها في سجل العمليات.
النتيجة تفرز: تعمل ✅ / رصيد غير كافٍ ⚠️ / مرفوضة ⛔ / منتهية / CVV خاطئ / احتيالية / غير معروفة / بيانات غير صالحة ❌.
يوجد زر «تحميل قالب» في اللوحة، ونموذج جاهز في `public/cards-sample.csv`.

مثال:
```bash
curl -X POST http://localhost:3000/api/payments/verify \
  -H "Content-Type: application/json" \
  -d '{"amount":100,"card":{"number":"4242424242424242","expMonth":"12","expYear":"29","cvv":"123","holderName":"TEST USER"}}'
```

## الانتقال إلى بوابة دفع حقيقية

### Stripe
1. في `.env`: `PAYMENT_GATEWAY=stripe` + المفاتيح.
2. في الواجهة استخدم **Stripe.js / Elements** لتحويل البطاقة إلى `paymentMethodId` وأرسله للخادم بدلاً من رقم البطاقة (متطلب PCI-DSS).
3. `stripeGateway.js` ينشئ `PaymentIntent` بـ `capture_method: 'manual'` = حجز مسبق. مدة الحجز في Stripe تصل إلى 7 أيام.

> ⚠️ **ملاحظة للمغرب:** Stripe لا يفتح حسابات للتجار المقيمين في المغرب مباشرة (يتطلب شركة مسجلة في الخارج مثل Stripe Atlas) ولا يقبل البطاقات المغربية المحلية. لقبول البطاقات المغربية تحتاج **CMI** أو **PayZone** أو **Chari Pay** عبر البنك.

### CMI / PayZone / أي بوابة أخرى
أنشئ ملفاً `src/gateways/cmiGateway.js` ينفذ نفس الواجهة:
```js
class CmiGateway {
  async authorize({ amount, currency, ... }) { /* طلب PreAuth */ return { ok, authorizationId, code, message } }
  async capture({ authorizationId, amount })   { /* PostAuth */ }
  async cancel({ authorizationId })            { /* Void */ }
}
```
ثم أضفه في `src/gateways/index.js`. باقي المشروع لن يتغير.

## بنية الملفات
```
payment-verify/
├── src/
│   ├── server.js              # Express + المسارات
│   ├── paymentService.js      # منطق العمل + الحماية من التكرار + السجل
│   ├── cardValidator.js       # Luhn / نوع البطاقة / الانتهاء / CVV
│   └── gateways/
│       ├── index.js           # اختيار البوابة من .env
│       ├── mockGateway.js     # محاكاة البنك للتطوير
│       └── stripeGateway.js   # Stripe الحقيقي
├── public/index.html          # واجهة تجريبية عربية
├── test/                      # اختبارات (node --test)
├── .env.example
└── README.md
```

## قبل الإنتاج (Checklist)
- [ ] لا ترسل رقم البطاقة لخادمك أبداً — استخدم Tokenization (Stripe.js / صفحة CMI المستضافة).
- [ ] فعّل HTTPS و 3D Secure.
- [ ] استبدل `transactions` و `attempts` (في الذاكرة) بقاعدة بيانات و Redis.
- [ ] اربط `clientKey` بـ userId الحقيقي بعد تسجيل الدخول.
- [ ] غيّر `ADMIN_KEY` واستبدله لاحقاً بنظام دخول حقيقي (JWT/جلسات) للوحة الأدمن.
- [ ] استخدم Webhooks من البوابة لتأكيد الحالات النهائية بدلاً من الاعتماد على الرد المباشر فقط.
- [ ] أضف مهمة مجدولة لإلغاء الحجوزات التي لم تُسحب قبل انتهاء مدتها.
