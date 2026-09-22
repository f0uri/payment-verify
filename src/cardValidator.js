/**
 * cardValidator.js
 * -----------------------------------------------------------
 * المستوى الأول من الحماية: التحقق "الشكلي" من البطاقة قبل إرسالها للبنك.
 * هذا المستوى يمنع البطاقات المكتوبة عشوائياً (أرقام وهمية) لكنه لا يثبت
 * أن البطاقة حقيقية أو أن فيها رصيداً — ذلك يتم فقط عبر البنك (Pre-Authorization).
 */

const BRANDS = [
  { name: 'visa',       pattern: /^4\d{12}(\d{3})?(\d{3})?$/,                       cvvLength: 3 },
  { name: 'mastercard', pattern: /^(5[1-5]\d{14}|2(2[2-9]\d|[3-6]\d{2}|7[01]\d|720)\d{12})$/, cvvLength: 3 },
  { name: 'amex',       pattern: /^3[47]\d{13}$/,                                    cvvLength: 4 },
  { name: 'discover',   pattern: /^6(?:011|5\d{2})\d{12}$/,                          cvvLength: 3 },
  { name: 'cmi',        pattern: /^6(?:04|27)\d{13}$/,                                cvvLength: 3 }, // بطاقات محلية مغربية (تقريبي)
];

/** إزالة المسافات والشرطات من رقم البطاقة */
function normalize(number = '') {
  return String(number).replace(/[\s-]/g, '');
}

/** خوارزمية Luhn — كل البطاقات الحقيقية تمر بها */
function luhnCheck(number) {
  const digits = normalize(number);
  if (!/^\d{12,19}$/.test(digits)) return false;
  let sum = 0;
  let shouldDouble = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = parseInt(digits[i], 10);
    if (shouldDouble) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    shouldDouble = !shouldDouble;
  }
  return sum % 10 === 0;
}

/** التعرف على نوع البطاقة من رقمها */
function detectBrand(number) {
  const digits = normalize(number);
  const brand = BRANDS.find((b) => b.pattern.test(digits));
  return brand ? brand.name : 'unknown';
}

/** التحقق من تاريخ الانتهاء MM/YY أو MM/YYYY */
function validateExpiry(month, year) {
  const m = parseInt(month, 10);
  let y = parseInt(year, 10);
  if (Number.isNaN(m) || Number.isNaN(y)) return { ok: false, reason: 'تاريخ الانتهاء غير صالح' };
  if (m < 1 || m > 12) return { ok: false, reason: 'الشهر يجب أن يكون بين 01 و 12' };
  if (y < 100) y += 2000;
  const now = new Date();
  const expiry = new Date(y, m, 0, 23, 59, 59); // آخر يوم في الشهر
  if (expiry < now) return { ok: false, reason: 'البطاقة منتهية الصلاحية' };
  if (y > now.getFullYear() + 20) return { ok: false, reason: 'تاريخ الانتهاء بعيد جداً وغير منطقي' };
  return { ok: true, month: m, year: y };
}

/** التحقق من رمز الأمان CVV */
function validateCvv(cvv, brand) {
  const s = String(cvv || '').trim();
  const expected = brand === 'amex' ? 4 : 3;
  if (!/^\d+$/.test(s)) return { ok: false, reason: 'رمز CVV يجب أن يكون أرقاماً فقط' };
  if (s.length !== expected) return { ok: false, reason: `رمز CVV يجب أن يتكون من ${expected} أرقام` };
  return { ok: true };
}

/**
 * الدالة الرئيسية: تتحقق من كل حقول البطاقة وتُرجع قائمة الأخطاء.
 * @returns {{ ok: boolean, errors: string[], brand: string, last4: string }}
 */
function validateCard({ number, expMonth, expYear, cvv, holderName }) {
  const errors = [];
  const digits = normalize(number);

  if (!digits) errors.push('رقم البطاقة مطلوب');
  else if (!/^\d{12,19}$/.test(digits)) errors.push('رقم البطاقة يجب أن يتكون من 12 إلى 19 رقماً');
  else if (!luhnCheck(digits)) errors.push('رقم البطاقة غير صحيح (فشل في فحص Luhn) — قد يكون رقماً وهمياً');

  const brand = detectBrand(digits);
  if (digits && brand === 'unknown') errors.push('نوع البطاقة غير معروف أو غير مدعوم');

  const exp = validateExpiry(expMonth, expYear);
  if (!exp.ok) errors.push(exp.reason);

  const c = validateCvv(cvv, brand);
  if (!c.ok) errors.push(c.reason);

  if (!holderName || String(holderName).trim().length < 3) errors.push('اسم حامل البطاقة مطلوب');

  return {
    ok: errors.length === 0,
    errors,
    brand,
    last4: digits.slice(-4),
  };
}

module.exports = { normalize, luhnCheck, detectBrand, validateExpiry, validateCvv, validateCard };
