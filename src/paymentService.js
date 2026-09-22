/**
 * paymentService.js
 * -----------------------------------------------------------
 * منطق العمل: يجمع بين التحقق الشكلي + الحجز المسبق لدى البنك + حماية من
 * محاولات "تخمين البطاقات" (Card Testing) + سجل العمليات.
 */
const { validateCard } = require('./cardValidator');
const { parseCardFile, MAX_CARDS } = require('./cardFileParser');

// الإعدادات الافتراضية — يتحكم فيها الأدمن عبر /api/admin/settings
const DEFAULT_SETTINGS = {
  maxFailedAttempts: 5,   // عدد المحاولات الفاشلة المسموح بها قبل الحظر
  blockMinutes: 15,       // مدة الحظر بالدقائق
  minAmount: 5,           // أقل مبلغ مسموح به
  maxAmount: 20000,       // أعلى مبلغ مسموح به
};

class PaymentService {
  constructor(gateway, settings = {}) {
    this.gateway = gateway;
    this.settings = { ...DEFAULT_SETTINGS, ...settings };
    this.attempts = new Map();     // key (IP أو userId) -> { count, blockedUntil }
    this.transactions = [];        // سجل العمليات (استبدله بقاعدة بيانات لاحقاً)
  }

  // ---------- إعدادات الأدمن ----------
  getSettings() { return { ...this.settings }; }

  updateSettings(patch = {}) {
    const errors = [];
    const next = { ...this.settings };
    const rules = {
      maxFailedAttempts: [1, 100, 'عدد المحاولات يجب أن يكون بين 1 و 100'],
      blockMinutes: [1, 10080, 'مدة الحظر يجب أن تكون بين 1 دقيقة و 7 أيام'],
      minAmount: [0, 1e9, 'الحد الأدنى غير صالح'],
      maxAmount: [1, 1e9, 'الحد الأعلى غير صالح'],
    };
    for (const [k, [lo, hi, msg]] of Object.entries(rules)) {
      if (patch[k] === undefined) continue;
      const v = Number(patch[k]);
      if (!Number.isFinite(v) || v < lo || v > hi) errors.push(msg); else next[k] = v;
    }
    if (next.minAmount >= next.maxAmount) errors.push('الحد الأدنى يجب أن يكون أقل من الحد الأعلى');
    if (errors.length) return { ok: false, errors };
    this.settings = next;
    return { ok: true, settings: this.getSettings() };
  }

  /** قائمة العملاء المحظورين أو الذين لديهم محاولات فاشلة */
  listAttempts() {
    const now = Date.now();
    return [...this.attempts.entries()].map(([key, r]) => ({
      key,
      failedCount: r.count,
      blocked: !!(r.blockedUntil && r.blockedUntil > now),
      blockedUntil: r.blockedUntil && r.blockedUntil > now ? new Date(r.blockedUntil).toISOString() : null,
    }));
  }

  /** رفع الحظر وتصفير المحاولات لعميل معين */
  unblock(key) {
    const existed = this.attempts.delete(key);
    return { ok: true, existed };
  }

  /** حظر عميل يدوياً */
  block(key, minutes = this.settings.blockMinutes) {
    this.attempts.set(key, { count: 0, blockedUntil: Date.now() + minutes * 60 * 1000 });
    return { ok: true };
  }

  resetAllAttempts() {
    const n = this.attempts.size;
    this.attempts.clear();
    return { ok: true, cleared: n };
  }

  // ---------- الحماية من المحاولات المتكررة ----------
  _checkBlocked(key) {
    const rec = this.attempts.get(key);
    if (rec && rec.blockedUntil && rec.blockedUntil > Date.now()) {
      const mins = Math.ceil((rec.blockedUntil - Date.now()) / 60000);
      return `تم حظر المحاولات مؤقتاً بسبب كثرة الأخطاء. حاول بعد ${mins} دقيقة`;
    }
    return null;
  }

  _recordFailure(key) {
    const rec = this.attempts.get(key) || { count: 0, blockedUntil: null };
    rec.count += 1;
    if (rec.count >= this.settings.maxFailedAttempts) {
      rec.blockedUntil = Date.now() + this.settings.blockMinutes * 60 * 1000;
      rec.count = 0;
    }
    this.attempts.set(key, rec);
  }

  _recordSuccess(key) {
    this.attempts.delete(key);
  }

  _log(entry) {
    const tx = { id: this.transactions.length + 1, at: new Date().toISOString(), ...entry };
    this.transactions.push(tx);
    return tx;
  }

  // ---------- الخطوة 1+2: التحقق الشكلي ثم الحجز لدى البنك ----------
  /**
   * @param {object} p
   * @param {string} p.clientKey  معرّف العميل (IP أو userId) لتتبع المحاولات
   * @param {number} p.amount     المبلغ المراد التحقق منه/حجزه
   * @param {object} [p.card]     بيانات البطاقة (وضع mock فقط)
   * @param {string} [p.paymentMethodId]  معرّف من Stripe.js (وضع stripe)
   */
  async verifyAndHold({ clientKey, amount, currency = 'MAD', card, paymentMethodId, holderName }) {
    const blocked = this._checkBlocked(clientKey);
    if (blocked) return { ok: false, stage: 'rate_limit', message: blocked };

    // التحقق من المبلغ
    const amt = Number(amount);
    const { minAmount, maxAmount } = this.settings;
    if (!Number.isFinite(amt) || amt < minAmount || amt > maxAmount) {
      return { ok: false, stage: 'amount', message: `المبلغ يجب أن يكون بين ${minAmount} و ${maxAmount} ${currency}` };
    }

    // المرحلة 1: التحقق الشكلي (فقط عندما تصلنا بيانات البطاقة مباشرة)
    let cardInfo = null;
    if (card) {
      const v = validateCard({ ...card, holderName: holderName || card.holderName });
      if (!v.ok) {
        this._recordFailure(clientKey);
        this._log({ stage: 'validation', ok: false, amount: amt, currency, errors: v.errors, last4: v.last4 });
        return { ok: false, stage: 'validation', message: 'بيانات البطاقة غير صالحة', errors: v.errors };
      }
      cardInfo = { brand: v.brand, last4: v.last4 };
    }

    // المرحلة 2: الحجز المسبق لدى البنك (الإثبات الحقيقي للرصيد وصحة البطاقة)
    const result = await this.gateway.authorize({ card, paymentMethodId, amount: amt, currency });

    if (!result.ok) {
      this._recordFailure(clientKey);
      this._log({ stage: 'bank', ok: false, amount: amt, currency, code: result.code, last4: cardInfo?.last4 });
      return { ok: false, stage: 'bank', code: result.code, status: result.status, message: result.message, clientSecret: result.clientSecret };
    }

    this._recordSuccess(clientKey);
    const tx = this._log({
      stage: 'bank', ok: true, status: 'authorized',
      authorizationId: result.authorizationId, amount: amt, currency,
      card: result.card || cardInfo,
    });

    return {
      ok: true,
      stage: 'bank',
      message: 'البطاقة صالحة والرصيد كافٍ — تم حجز المبلغ',
      authorizationId: result.authorizationId,
      amount: amt,
      currency,
      card: result.card || cardInfo,
      remainingBalance: result.remainingBalance, // ديمو فقط
      transactionId: tx.id,
    };
  }

  // ---------- الخطوة 3: السحب الفعلي بعد إتمام الشحن ----------
  async capture({ authorizationId, amount, currency }) {
    const r = await this.gateway.capture({ authorizationId, amount, currency });
    this._log({ stage: 'capture', ok: r.ok, authorizationId, amount: r.amount ?? amount, code: r.code });
    return r.ok ? { ok: true, message: 'تم سحب المبلغ بنجاح', ...r } : { ok: false, message: r.message, code: r.code };
  }

  // ---------- إلغاء الحجز وإرجاع المبلغ ----------
  async cancel({ authorizationId }) {
    const r = await this.gateway.cancel({ authorizationId });
    this._log({ stage: 'cancel', ok: r.ok, authorizationId, code: r.code });
    return r.ok ? { ok: true, message: 'تم إلغاء الحجز وإرجاع المبلغ' } : { ok: false, message: r.message, code: r.code };
  }

  // ---------- فحص جماعي لملف بطاقات (للأدمن فقط) ----------
  /**
   * يفحص بطاقات ملف مرفوع: تحقق شكلي + حجز تجريبي فوري ثم إلغاؤه
   * (لا يُسحب أي مبلغ، ولا تُسجَّل كمحاولات فاشلة ولا تظهر في سجل العمليات).
   * @param {string} text محتوى الملف (CSV/TXT)
   */
  async checkCards(text) {
    const { cards, errors } = parseCardFile(text);
    if (!cards.length && !errors.length) {
      return { ok: false, message: 'الملف فارغ أو لا يحتوي بطاقات' };
    }

    // مبلغ الفحص: الحد الأدنى المسموح (على الأقل 1) — يُحجز ثم يُلغى فوراً
    const amt = Math.min(Math.max(Number(this.settings.minAmount) || 1, 1), this.settings.maxAmount);

    const checkOne = async ({ line, card }) => {
      const v = validateCard(card);
      if (!v.ok) return { line, number: card.number, status: 'invalid', message: v.errors[0], errors: v.errors };

      const r = await this.gateway.authorize({ card, amount: amt, currency: 'MAD' });
      if (!r.ok) return { line, brand: v.brand, last4: v.last4, status: r.code || 'declined', message: r.message };

      // البنك وافق → نلغي الحجز فوراً (فحص فقط بدون أي خصم)
      await this.gateway.cancel({ authorizationId: r.authorizationId });
      return { line, brand: v.brand, last4: v.last4, status: 'valid', message: 'البطاقة تعمل — الرصيد كافٍ (حجز تجريبي ثم أُلغي)' };
    };

    // فحص متوازٍ على دفعات لتقليل زمن الاستجابة
    const results = [];
    const CHUNK = 8;
    for (let i = 0; i < cards.length; i += CHUNK) {
      results.push(...(await Promise.all(cards.slice(i, i + CHUNK).map(checkOne))));
    }

    const summary = {
      total: results.length + errors.length,
      parsed: results.length,
      skippedLines: errors.length,
      maxCards: MAX_CARDS,
    };
    for (const r of results) summary[r.status] = (summary[r.status] || 0) + 1;
    summary.bad_line = errors.length;

    return {
      ok: true,
      summary,
      results: [...results, ...errors.map((e) => ({ line: e.line, status: 'bad_line', message: e.message }))]
        .sort((a, b) => a.line - b.line),
    };
  }

  listTransactions() {
    return [...this.transactions].reverse();
  }
}

module.exports = { PaymentService, DEFAULT_SETTINGS };
