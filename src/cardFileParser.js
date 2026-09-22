/**
 * cardFileParser.js
 * -----------------------------------------------------------
 * تحليل ملف نصي (CSV/TXT) يحتوي بطاقات دفع لفحصها جماعياً من لوحة الأدمن.
 *
 * صيغة كل سطر:  number, expMonth, expYear, cvv, holderName?
 *   - الفواصل المقبولة: , أو ; أو Tab أو |
 *   - السطر الأول يُتخطى تلقائياً إذا كان ترويسة (يبدأ بحروف لا أرقام)
 *   - الأسطر الفارغة تُتجاهل، وعمود الاسم اختياري
 *   - الحد الأقصى MAX_CARDS بطاقة في المرة الواحدة (حماية من ملفات ضخمة)
 */

const MAX_CARDS = 200;

function parseCardFile(text) {
  const cards = [];   // [{ line, card: { number, expMonth, expYear, cvv, holderName } }]
  const errors = [];  // [{ line, message }]
  const lines = String(text || '').replace(/^\uFEFF/, '').split(/\r?\n/);
  let firstNonEmpty = true;
  let limitNotified = false;

  lines.forEach((raw, i) => {
    const line = raw.trim();
    if (!line) return;

    if (cards.length >= MAX_CARDS) {
      if (!limitNotified) {
        errors.push({ line: i + 1, message: `تم تجاهل باقي الملف — الحد الأقصى ${MAX_CARDS} بطاقة في المرة الواحدة` });
        limitNotified = true;
      }
      return;
    }

    const fields = line.split(/[;,\t|]/).map((f) => f.trim());

    // تخطي سطر الترويسة (أول سطر غير فارغ يبدأ بحروف)
    if (firstNonEmpty && fields.length > 1 && /[a-zA-Z\u0600-\u06FF]/.test(fields[0])) {
      firstNonEmpty = false;
      return;
    }
    firstNonEmpty = false;

    if (fields.length < 4) {
      errors.push({ line: i + 1, message: 'صيغة غير صالحة — كل سطر: الرقم، الشهر، السنة، CVV، الاسم (اختياري)' });
      return;
    }

    cards.push({
      line: i + 1,
      card: {
        number: fields[0],
        expMonth: fields[1],
        expYear: fields[2],
        cvv: fields[3],
        holderName: fields.slice(4).join(' ').trim() || 'بطاقة من ملف',
      },
    });
  });

  return { cards, errors };
}

module.exports = { parseCardFile, MAX_CARDS };
