/**
 * mockGateway.js
 * -----------------------------------------------------------
 * بوابة دفع وهمية "تحاكي البنك" للتطوير والاختبار بدون أي حساب حقيقي.
 * تنفذ نفس الواجهة التي تنفذها StripeGateway حتى يمكن التبديل بينهما
 * بتغيير متغير واحد في ملف .env (PAYMENT_GATEWAY=mock | stripe).
 *
 * أرقام البطاقات التجريبية (نفس أرقام Stripe لتسهيل الانتقال لاحقاً):
 *   4242 4242 4242 4242  -> بطاقة سليمة، رصيدها 1000 MAD
 *   5555 5555 5555 4444  -> Mastercard سليمة، رصيدها 250 MAD
 *   4000 0000 0000 9995  -> رصيد غير كافٍ (insufficient_funds)
 *   4000 0000 0000 0002  -> مرفوضة من البنك (generic_decline)
 *   4000 0000 0000 0069  -> منتهية الصلاحية (expired_card)
 *   4000 0000 0000 0127  -> رمز CVV خاطئ (incorrect_cvc)
 *   4000 0000 0000 0341  -> مشتبه بها/احتيال (fraudulent)
 *   أي رقم آخر (حتى لو مرّ بـ Luhn) -> بطاقة غير موجودة لدى البنك (card_not_found)
 */

const crypto = require('crypto');
const { normalize } = require('../cardValidator');

const TEST_CARDS = {
  '4242424242424242': { balance: 1000, brand: 'visa' },
  '5555555555554444': { balance: 250, brand: 'mastercard' },
  '4000000000009995': { balance: 0, brand: 'visa', decline: 'insufficient_funds' },
  '4000000000000002': { balance: 1000, brand: 'visa', decline: 'generic_decline' },
  '4000000000000069': { balance: 1000, brand: 'visa', decline: 'expired_card' },
  '4000000000000127': { balance: 1000, brand: 'visa', decline: 'incorrect_cvc' },
  '4000000000000341': { balance: 1000, brand: 'visa', decline: 'fraudulent' },
};

const DECLINE_MESSAGES = {
  insufficient_funds: 'الرصيد غير كافٍ في البطاقة',
  generic_decline: 'رفض البنك العملية',
  expired_card: 'البطاقة منتهية الصلاحية',
  incorrect_cvc: 'رمز الأمان CVV غير صحيح',
  fraudulent: 'تم رفض البطاقة للاشتباه في عملية احتيال',
  card_not_found: 'البطاقة غير معروفة لدى البنك — قد تكون بطاقة وهمية',
  processing_error: 'خطأ أثناء معالجة العملية، حاول لاحقاً',
};

class MockGateway {
  constructor() {
    this.name = 'mock';
    // رصيد كل بطاقة يتغير مع العمليات (محاكاة بسيطة)
    this.balances = Object.fromEntries(
      Object.entries(TEST_CARDS).map(([n, c]) => [n, c.balance])
    );
    this.authorizations = new Map(); // authId -> { card, amount, status }
  }

  async _delay() {
    return new Promise((r) => setTimeout(r, 400)); // محاكاة زمن استجابة البنك
  }

  /**
   * الحجز المسبق (Pre-Authorization / Hold):
   * نطلب من البنك حجز المبلغ بدون سحبه. إذا وافق البنك => البطاقة حقيقية
   * وفيها رصيد كافٍ. إذا رفض => نعرف السبب بالضبط.
   */
  async authorize({ card, amount, currency = 'MAD' }) {
    await this._delay();
    const number = normalize(card.number);
    const info = TEST_CARDS[number];

    if (!info) return this._declined('card_not_found');
    if (info.decline) return this._declined(info.decline);
    if (this.balances[number] < amount) return this._declined('insufficient_funds');

    // البنك وافق: نحجز المبلغ
    this.balances[number] -= amount;
    const id = 'auth_' + crypto.randomBytes(8).toString('hex');
    this.authorizations.set(id, { number, amount, currency, status: 'authorized' });

    return {
      ok: true,
      authorizationId: id,
      status: 'authorized',
      amount,
      currency,
      card: { brand: info.brand, last4: number.slice(-4) },
      remainingBalance: this.balances[number], // للعرض في الديمو فقط، البنك الحقيقي لا يعطيه
    };
  }

  /** سحب المبلغ المحجوز فعلياً (كله أو جزء منه) */
  async capture({ authorizationId, amount }) {
    await this._delay();
    const auth = this.authorizations.get(authorizationId);
    if (!auth) return { ok: false, code: 'not_found', message: 'الحجز غير موجود' };
    if (auth.status !== 'authorized') return { ok: false, code: 'invalid_state', message: `الحجز في حالة ${auth.status}` };
    const toCapture = amount ?? auth.amount;
    if (toCapture > auth.amount) return { ok: false, code: 'amount_too_large', message: 'لا يمكن سحب أكثر من المبلغ المحجوز' };
    // إرجاع الفرق للبطاقة إذا سحبنا أقل من المحجوز
    this.balances[auth.number] += auth.amount - toCapture;
    auth.status = 'captured';
    auth.captured = toCapture;
    return { ok: true, status: 'captured', amount: toCapture, currency: auth.currency };
  }

  /** إلغاء الحجز وإرجاع المبلغ للبطاقة */
  async cancel({ authorizationId }) {
    await this._delay();
    const auth = this.authorizations.get(authorizationId);
    if (!auth) return { ok: false, code: 'not_found', message: 'الحجز غير موجود' };
    if (auth.status !== 'authorized') return { ok: false, code: 'invalid_state', message: `الحجز في حالة ${auth.status}` };
    this.balances[auth.number] += auth.amount;
    auth.status = 'canceled';
    return { ok: true, status: 'canceled' };
  }

  _declined(code) {
    return { ok: false, status: 'declined', code, message: DECLINE_MESSAGES[code] || DECLINE_MESSAGES.processing_error };
  }
}

module.exports = { MockGateway, TEST_CARDS, DECLINE_MESSAGES };
