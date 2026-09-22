/**
 * stripeGateway.js
 * -----------------------------------------------------------
 * بوابة Stripe الحقيقية — نفس واجهة MockGateway.
 * تُفعَّل بوضع PAYMENT_GATEWAY=stripe و STRIPE_SECRET_KEY في ملف .env
 *
 * ملاحظة أمنية مهمة (PCI-DSS):
 *   رقم البطاقة لا يجب أن يصل إلى خادمك أبداً في الإنتاج.
 *   الواجهة الأمامية تستخدم Stripe.js لتحويل البطاقة إلى paymentMethodId
 *   ثم ترسل هذا المعرّف فقط إلى الخادم. لذلك authorize() هنا تستقبل
 *   paymentMethodId وليس رقم البطاقة.
 *
 * ملاحظة للمغرب:
 *   Stripe لا يفتح حسابات للتجار في المغرب مباشرة (يحتاج شركة مسجلة في
 *   الخارج مثل Stripe Atlas). لقبول البطاقات المغربية المحلية استخدم CMI
 *   أو PayZone — يمكنك إضافة gateways/cmiGateway.js بنفس الواجهة.
 */

const DECLINE_MESSAGES = {
  insufficient_funds: 'الرصيد غير كافٍ في البطاقة',
  generic_decline: 'رفض البنك العملية',
  expired_card: 'البطاقة منتهية الصلاحية',
  incorrect_cvc: 'رمز الأمان CVV غير صحيح',
  incorrect_number: 'رقم البطاقة غير صحيح',
  fraudulent: 'تم رفض البطاقة للاشتباه في عملية احتيال',
  card_velocity_exceeded: 'تم تجاوز الحد المسموح للعمليات',
  lost_card: 'البطاقة مُبلَّغ عن فقدانها',
  stolen_card: 'البطاقة مُبلَّغ عن سرقتها',
  processing_error: 'خطأ أثناء معالجة العملية، حاول لاحقاً',
};

// العملات التي لا تحتوي على كسور (Stripe يتعامل معها بدون ×100)
const ZERO_DECIMAL = new Set(['JPY', 'KRW', 'XOF', 'XAF', 'CLP', 'VND']);
const toMinor = (amount, currency) => (ZERO_DECIMAL.has(currency.toUpperCase()) ? Math.round(amount) : Math.round(amount * 100));
const fromMinor = (amount, currency) => (ZERO_DECIMAL.has(currency.toUpperCase()) ? amount : amount / 100);

class StripeGateway {
  constructor(secretKey) {
    if (!secretKey) throw new Error('STRIPE_SECRET_KEY غير موجود في ملف .env');
    this.stripe = require('stripe')(secretKey);
    this.name = 'stripe';
  }

  /**
   * الحجز المسبق: PaymentIntent بـ capture_method = manual
   * إذا وصل إلى status = requires_capture => البطاقة حقيقية وفيها رصيد كافٍ.
   */
  async authorize({ paymentMethodId, amount, currency = 'MAD', customerEmail, metadata = {} }) {
    if (!paymentMethodId) {
      return { ok: false, status: 'error', code: 'missing_payment_method', message: 'paymentMethodId مطلوب (من Stripe.js في الواجهة)' };
    }
    try {
      const intent = await this.stripe.paymentIntents.create({
        amount: toMinor(amount, currency),
        currency: currency.toLowerCase(),
        payment_method: paymentMethodId,
        capture_method: 'manual',   // <-- هذا ما يجعلها "حجزاً" وليس سحباً
        confirm: true,
        receipt_email: customerEmail || undefined,
        metadata,
        automatic_payment_methods: { enabled: true, allow_redirects: 'never' },
      });

      if (intent.status === 'requires_capture') {
        const pm = intent.payment_method_details || {};
        return {
          ok: true,
          authorizationId: intent.id,
          status: 'authorized',
          amount: fromMinor(intent.amount, currency),
          currency: currency.toUpperCase(),
          card: intent.charges?.data?.[0]?.payment_method_details?.card
            ? { brand: intent.charges.data[0].payment_method_details.card.brand, last4: intent.charges.data[0].payment_method_details.card.last4 }
            : null,
        };
      }
      if (intent.status === 'requires_action') {
        // 3D Secure: يجب على الواجهة إكمال التحقق ثم إعادة المحاولة
        return { ok: false, status: 'requires_action', clientSecret: intent.client_secret, message: 'يتطلب تحقق 3D Secure' };
      }
      return { ok: false, status: intent.status, code: 'unexpected_status', message: `حالة غير متوقعة: ${intent.status}` };
    } catch (err) {
      return this._mapError(err);
    }
  }

  async capture({ authorizationId, amount, currency = 'MAD' }) {
    try {
      const intent = await this.stripe.paymentIntents.capture(authorizationId, amount != null ? { amount_to_capture: toMinor(amount, currency) } : {});
      return { ok: true, status: 'captured', amount: fromMinor(intent.amount_received, currency), currency: currency.toUpperCase() };
    } catch (err) {
      return this._mapError(err);
    }
  }

  async cancel({ authorizationId }) {
    try {
      await this.stripe.paymentIntents.cancel(authorizationId);
      return { ok: true, status: 'canceled' };
    } catch (err) {
      return this._mapError(err);
    }
  }

  _mapError(err) {
    if (err.type === 'StripeCardError') {
      const code = err.decline_code || err.code || 'generic_decline';
      return { ok: false, status: 'declined', code, message: DECLINE_MESSAGES[code] || err.message };
    }
    return { ok: false, status: 'error', code: err.code || 'processing_error', message: err.message };
  }
}

module.exports = { StripeGateway };
