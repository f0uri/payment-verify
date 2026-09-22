require('dotenv').config();
const path = require('path');
const express = require('express');
const { createGateway } = require('./gateways');
const { PaymentService } = require('./paymentService');
const { TEST_CARDS } = require('./gateways/mockGateway');

const app = express();
const PORT = process.env.PORT || 3000;

const gateway = createGateway();
const payments = new PaymentService(gateway);

app.use(express.json());
app.use(express.static(path.join(__dirname, '..', 'public')));

// معرّف العميل لتتبع المحاولات (استبدله بـ userId بعد تسجيل الدخول)
const clientKey = (req) => req.headers['x-user-id'] || req.ip;

// معلومات عن الوضع الحالي (للواجهة)
app.get('/api/config', (req, res) => {
  res.json({
    gateway: gateway.name,
    currency: process.env.CURRENCY || 'MAD',
    stripePublishableKey: process.env.STRIPE_PUBLISHABLE_KEY || null,
    testCards: gateway.name === 'mock'
      ? Object.entries(TEST_CARDS).map(([n, c]) => ({ number: n.replace(/(\d{4})(?=\d)/g, '$1 '), brand: c.brand, balance: c.balance, decline: c.decline || null }))
      : [],
  });
});

/**
 * POST /api/payments/verify
 * body: { amount, card:{number,expMonth,expYear,cvv,holderName} }   (mock)
 *       { amount, paymentMethodId }                                   (stripe)
 * يتحقق من البطاقة ويحجز المبلغ. لا يسحب المال.
 */
app.post('/api/payments/verify', async (req, res) => {
  try {
    const { amount, card, paymentMethodId, holderName } = req.body || {};
    const result = await payments.verifyAndHold({
      clientKey: clientKey(req),
      amount,
      currency: process.env.CURRENCY || 'MAD',
      card,
      paymentMethodId,
      holderName,
    });
    res.status(result.ok ? 200 : 402).json(result);
  } catch (err) {
    console.error(err);
    res.status(500).json({ ok: false, message: 'خطأ داخلي في الخادم' });
  }
});

/** POST /api/payments/capture  body: { authorizationId, amount? } — سحب المبلغ بعد إتمام الشحن */
app.post('/api/payments/capture', async (req, res) => {
  const { authorizationId, amount } = req.body || {};
  if (!authorizationId) return res.status(400).json({ ok: false, message: 'authorizationId مطلوب' });
  const r = await payments.capture({ authorizationId, amount: amount != null ? Number(amount) : undefined, currency: process.env.CURRENCY || 'MAD' });
  res.status(r.ok ? 200 : 400).json(r);
});

/** POST /api/payments/cancel  body: { authorizationId } — إلغاء الحجز */
app.post('/api/payments/cancel', async (req, res) => {
  const { authorizationId } = req.body || {};
  if (!authorizationId) return res.status(400).json({ ok: false, message: 'authorizationId مطلوب' });
  const r = await payments.cancel({ authorizationId });
  res.status(r.ok ? 200 : 400).json(r);
});

/** GET /api/payments — سجل العمليات */
app.get('/api/payments', (req, res) => res.json(payments.listTransactions()));

// =====================  لوحة الأدمن  =====================
// الحماية بمفتاح سري من .env (ADMIN_KEY) يُرسل في الهيدر X-Admin-Key
const ADMIN_KEY = process.env.ADMIN_KEY || 'admin1234';
const requireAdmin = (req, res, next) => {
  if (req.headers['x-admin-key'] !== ADMIN_KEY) return res.status(401).json({ ok: false, message: 'مفتاح الأدمن غير صحيح' });
  next();
};

app.post('/api/admin/login', (req, res) => {
  const ok = (req.body || {}).key === ADMIN_KEY;
  res.status(ok ? 200 : 401).json({ ok, message: ok ? 'تم الدخول' : 'مفتاح الأدمن غير صحيح' });
});

/** الإعدادات: عدد المحاولات، مدة الحظر، حدود المبلغ */
app.get('/api/admin/settings', requireAdmin, (req, res) => res.json({ ok: true, settings: payments.getSettings() }));
app.put('/api/admin/settings', requireAdmin, (req, res) => {
  const r = payments.updateSettings(req.body || {});
  res.status(r.ok ? 200 : 400).json(r);
});

/** المحاولات والحظر */
app.get('/api/admin/attempts', requireAdmin, (req, res) => res.json({ ok: true, attempts: payments.listAttempts() }));
app.delete('/api/admin/attempts', requireAdmin, (req, res) => res.json(payments.resetAllAttempts()));
app.delete('/api/admin/attempts/:key', requireAdmin, (req, res) => res.json(payments.unblock(req.params.key)));
app.post('/api/admin/attempts/:key/block', requireAdmin, (req, res) => res.json(payments.block(req.params.key, Number((req.body || {}).minutes) || undefined)));

/** فحص جماعي لملف بطاقات (CSV/TXT) — المحتوى في حقل content */
app.post('/api/admin/cards/check', requireAdmin, async (req, res) => {
  const content = (req.body || {}).content;
  if (!content || !String(content).trim()) {
    return res.status(400).json({ ok: false, message: 'أرسل محتوى الملف في الحقل content' });
  }
  res.json(await payments.checkCards(String(content)));
});

/** إحصائيات سريعة */
app.get('/api/admin/stats', requireAdmin, (req, res) => {
  const tx = payments.listTransactions();
  const attempts = payments.listAttempts();
  res.json({
    ok: true,
    stats: {
      total: tx.length,
      authorized: tx.filter((t) => t.stage === 'bank' && t.ok).length,
      declinedByBank: tx.filter((t) => t.stage === 'bank' && !t.ok).length,
      rejectedByValidation: tx.filter((t) => t.stage === 'validation').length,
      captured: tx.filter((t) => t.stage === 'capture' && t.ok).length,
      canceled: tx.filter((t) => t.stage === 'cancel' && t.ok).length,
      blockedNow: attempts.filter((a) => a.blocked).length,
    },
  });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`✅ Server running on http://0.0.0.0:${PORT}  | gateway: ${gateway.name}`);
});
