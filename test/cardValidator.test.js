const test = require('node:test');
const assert = require('node:assert');
const { luhnCheck, detectBrand, validateCard, validateExpiry } = require('../src/cardValidator');
const { MockGateway } = require('../src/gateways/mockGateway');
const { PaymentService } = require('../src/paymentService');

test('Luhn يقبل الأرقام الصحيحة ويرفض الوهمية', () => {
  assert.ok(luhnCheck('4242 4242 4242 4242'));
  assert.ok(luhnCheck('5555555555554444'));
  assert.ok(!luhnCheck('1234567812345678'));
  assert.ok(!luhnCheck('4242424242424241'));
  assert.ok(!luhnCheck('abcd'));
});

test('التعرف على نوع البطاقة', () => {
  assert.equal(detectBrand('4242424242424242'), 'visa');
  assert.equal(detectBrand('5555555555554444'), 'mastercard');
  assert.equal(detectBrand('378282246310005'), 'amex');
});

test('تاريخ الانتهاء', () => {
  assert.ok(!validateExpiry('01', '20').ok);
  assert.ok(!validateExpiry('13', '30').ok);
  assert.ok(validateExpiry('12', '30').ok);
});

test('validateCard يجمع الأخطاء', () => {
  const r = validateCard({ number: '1234 5678 1234 5678', expMonth: '01', expYear: '20', cvv: '12', holderName: '' });
  assert.ok(!r.ok);
  assert.ok(r.errors.length >= 3);
});

test('PaymentService: رصيد كافٍ -> حجز ثم سحب', async () => {
  const s = new PaymentService(new MockGateway());
  const card = { number: '4242424242424242', expMonth: '12', expYear: '30', cvv: '123', holderName: 'TEST USER' };
  const r = await s.verifyAndHold({ clientKey: 'u1', amount: 100, card });
  assert.ok(r.ok, JSON.stringify(r));
  assert.equal(r.remainingBalance, 900);
  const c = await s.capture({ authorizationId: r.authorizationId, amount: 80 });
  assert.ok(c.ok);
  assert.equal(c.amount, 80);
});

test('PaymentService: رصيد غير كافٍ', async () => {
  const s = new PaymentService(new MockGateway());
  const card = { number: '4000000000009995', expMonth: '12', expYear: '30', cvv: '123', holderName: 'TEST USER' };
  const r = await s.verifyAndHold({ clientKey: 'u2', amount: 100, card });
  assert.ok(!r.ok);
  assert.equal(r.code, 'insufficient_funds');
});

test('PaymentService: بطاقة وهمية تمر بـ Luhn لكن البنك لا يعرفها', async () => {
  const s = new PaymentService(new MockGateway());
  const card = { number: '4111111111111111', expMonth: '12', expYear: '30', cvv: '123', holderName: 'TEST USER' };
  const r = await s.verifyAndHold({ clientKey: 'u3', amount: 50, card });
  assert.ok(!r.ok);
  assert.equal(r.code, 'card_not_found');
});

test('PaymentService: حظر بعد 5 محاولات فاشلة', async () => {
  const s = new PaymentService(new MockGateway());
  const card = { number: '1111', expMonth: '12', expYear: '30', cvv: '123', holderName: 'X' };
  let r;
  for (let i = 0; i < 6; i++) r = await s.verifyAndHold({ clientKey: 'u4', amount: 50, card });
  assert.equal(r.stage, 'rate_limit');
});

test('Admin: تغيير عدد المحاولات يُطبَّق فوراً', async () => {
  const s = new PaymentService(new MockGateway());
  const u = s.updateSettings({ maxFailedAttempts: 2, blockMinutes: 30 });
  assert.ok(u.ok);
  const card = { number: '1111', expMonth: '12', expYear: '30', cvv: '123', holderName: 'X' };
  let r;
  for (let i = 0; i < 3; i++) r = await s.verifyAndHold({ clientKey: 'u5', amount: 50, card });
  assert.equal(r.stage, 'rate_limit');
  assert.equal(s.listAttempts()[0].blocked, true);
  s.unblock('u5');
  r = await s.verifyAndHold({ clientKey: 'u5', amount: 50, card });
  assert.equal(r.stage, 'validation'); // لم يعد محظوراً
});

test('Admin: رفض إعدادات غير منطقية', () => {
  const s = new PaymentService(new MockGateway());
  assert.ok(!s.updateSettings({ maxFailedAttempts: 0 }).ok);
  assert.ok(!s.updateSettings({ minAmount: 500, maxAmount: 100 }).ok);
});
