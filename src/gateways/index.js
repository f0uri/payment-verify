/**
 * اختيار بوابة الدفع حسب متغير البيئة PAYMENT_GATEWAY
 * mock   -> للتطوير والاختبار (افتراضي)
 * stripe -> Stripe الحقيقي
 * لإضافة CMI أو PayZone أو غيرها: أنشئ ملفاً بنفس الواجهة (authorize / capture / cancel)
 * وأضفه هنا.
 */
const { MockGateway } = require('./mockGateway');
const { StripeGateway } = require('./stripeGateway');

function createGateway() {
  const type = (process.env.PAYMENT_GATEWAY || 'mock').toLowerCase();
  switch (type) {
    case 'stripe':
      return new StripeGateway(process.env.STRIPE_SECRET_KEY);
    case 'mock':
    default:
      return new MockGateway();
  }
}

module.exports = { createGateway };
