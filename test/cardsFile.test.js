const test = require('node:test');
const assert = require('node:assert');
const { parseCardFile, MAX_CARDS } = require('../src/cardFileParser');
const { MockGateway } = require('../src/gateways/mockGateway');
const { PaymentService } = require('../src/paymentService');

test('محلل الملف: يتخطى الترويسة ويقبل فواصل متعددة', () => {
  const text = [
    'number,expMonth,expYear,cvv,holderName',
    '4242424242424242,12,2030,123,Ahmed',
    '5555555555554444;06;2029;456;Sara',
    '4000000000009995|12|2030|123|Test',
    '4000000000000002\t12\t2030\t123',
  ].join('\n');
  const { cards, errors } = parseCardFile(text);
  assert.equal(errors.length, 0);
  assert.equal(cards.length, 4);
  assert.equal(cards[0].line, 2);
  assert.equal(cards[0].card.number, '4242424242424242');
  assert.equal(cards[1].card.holderName, 'Sara');
  assert.ok(cards[3].card.holderName.length >= 3); // اسم افتراضي عند غياب العمود
});

test('محلل الملف: يشير للأسطر السيئة ويحترم الحد الأقصى', () => {
  const bad = parseCardFile('4242424242424242,12,2030,123\nسطر-غير-مفهوم\n');
  assert.equal(bad.cards.length, 1);
  assert.equal(bad.errors.length, 1);
  assert.equal(bad.errors[0].line, 2);

  const many = parseCardFile(Array.from({ length: MAX_CARDS + 5 }, () => '4242424242424242,12,2030,123,X').join('\n'));
  assert.equal(many.cards.length, MAX_CARDS);
  assert.equal(many.errors.length, 1);
  assert.ok(many.errors[0].message.includes('الحد الأقصى'));
});

test('checkCards: يفرز البطاقات العاملة من غير العاملة', async () => {
  const s = new PaymentService(new MockGateway());
  const text = [
    'number,expMonth,expYear,cvv,holderName',
    '4242424242424242,12,2030,123,Ahmed',   // ✅ تعمل
    '4000000000009995,12,2030,123,Test',    // ⚠️ رصيد غير كافٍ
    '4000000000000341,12,2030,123,Test',    // ⛔ احتيالية
    '4111111111111111,12,2030,123,Test',    // ⛔ تمر بـ Luhn لكن غير موجودة
    '1111,12,2030,1,X',                     // ❌ فشل التحقق الشكلي
    'سطر-غير-مفهوم',                        // ✳️ سطر سيئ
  ].join('\n');
  const r = await s.checkCards(text);
  assert.ok(r.ok, JSON.stringify(r));
  assert.equal(r.summary.total, 6);
  assert.equal(r.summary.valid, 1);
  assert.equal(r.summary.insufficient_funds, 1);
  assert.equal(r.summary.fraudulent, 1);
  assert.equal(r.summary.card_not_found, 1);
  assert.equal(r.summary.invalid, 1);
  assert.equal(r.summary.bad_line, 1);
  assert.deepEqual(r.results.map((x) => x.line), [2, 3, 4, 5, 6, 7]); // مرتبة حسب السطر
});

test('checkCards: لا يخصم رصيداً ولا يسجل محاولات أو عمليات', async () => {
  const gw = new MockGateway();
  const s = new PaymentService(gw);
  const before = gw.balances['4242424242424242'];
  const r = await s.checkCards('4242424242424242,12,2030,123,Ahmed\n4000000000000002,12,2030,123,Test');
  assert.ok(r.ok);
  assert.equal(gw.balances['4242424242424242'], before); // الحجز التجريبي أُلغي
  assert.equal(s.listAttempts().length, 0);              // لا حظر ولا محاولات
  assert.equal(s.listTransactions().length, 0);          // لا تلوث لسجل العمليات
  const valid = r.results.find((x) => x.status === 'valid');
  assert.equal(valid.last4, '4242');
});

test('checkCards: يرفض ملفاً فارغاً', async () => {
  const s = new PaymentService(new MockGateway());
  const r = await s.checkCards('   \n  ');
  assert.ok(!r.ok);
});
