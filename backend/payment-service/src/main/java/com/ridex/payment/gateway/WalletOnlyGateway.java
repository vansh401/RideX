package com.ridex.payment.gateway;

/**
 * Used when a real online provider is active. Such a provider cannot be charged from our server (the rider must approve each payment in
 * its checkout window), so a ride the wallet cannot cover simply FAILS with a clear reason, and the rider pays the shortfall through the
 * checkout ("Pay now"), which credits the wallet and retries the payment. There is never an automatic card charge.
 */
public class WalletOnlyGateway implements PaymentGateway {

    static final String REASON = "Not enough money in your wallet. Add money to pay for this ride.";

    @Override
    public ChargeResult charge(ChargeRequest request) {
        return new ChargeResult(false, null, REASON);
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        // Nothing was ever charged to a card by us, so there is nothing to refund to one: refunds go back to the wallet.
        return new RefundResult(false, null, "Refunds are returned to the wallet");
    }
}
