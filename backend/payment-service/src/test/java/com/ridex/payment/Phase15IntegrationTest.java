package com.ridex.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ridex.payment.extras.CancellationFeeService;
import com.ridex.payment.extras.PayoutService;
import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.platform.testing.TestTokens;

/**
 * Phase 15, the money features around the ledger: online payments (the test checkout stands in for Razorpay), tips, receipts, cancellation
 * fees, withdrawals, referrals, manual adjustments, offers and incentive progress. Real PostgreSQL, Redis and Kafka.
 */
class Phase15IntegrationTest extends PaymentTestBase {

    private static final String MOCK_SIGNATURE = "mock-signature";

    @Autowired
    private PaymentService payments;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private LedgerAccounts accounts;
    @Autowired
    private CancellationFeeService cancellationFees;
    @Autowired
    private PayoutService payouts;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Http http;
    private String admin;

    @BeforeEach
    void client() {
        http = new Http(port);
        admin = TestTokens.token(id(), "ADMIN");
    }

    private static BigDecimal bd(Object value) {
        return new BigDecimal(String.valueOf(value)).setScale(2);
    }

    private BigDecimal balance(UUID account) {
        return ledger.balanceOf(account);
    }

    private UUID fundedWallet(UUID rider, String amount) {
        UUID wallet = accounts.walletOf(rider);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> ledger.post("TOPUP", "test", "fund-" + wallet + "-" + id(), "test",
                List.of(new LedgerService.Leg(AccountType.GATEWAY_ID, new BigDecimal(amount).negate()), new LedgerService.Leg(wallet, new BigDecimal(amount)))));
        return wallet;
    }

    private Payment paidRide(UUID ride, UUID rider, UUID driver, String fare) {
        Payment p = payments.chargeForCompletedRide(ride, rider, driver, new BigDecimal(fare), "INR", null);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        return p;
    }

    private String confirmBody(String orderId, String paymentId) {
        return "{\"orderId\":\"" + orderId + "\",\"paymentId\":\"" + paymentId + "\",\"signature\":\"" + MOCK_SIGNATURE + "\"}";
    }

    // ------------------------------------------------------------------ online payments

    @Test
    void anOnlineTopUpCreditsTheWalletExactlyOnceHoweverManyTimesItIsConfirmed() {
        UUID rider = id();
        String token = TestTokens.token(rider, "RIDER");
        assertThat((String) http.get("/api/payments/online/config", token).json("$.provider")).isEqualTo("MOCK");

        Http.Response order = http.post("/api/payments/online/orders", token, "{\"purpose\":\"TOPUP\",\"amount\":300}");
        assertThat(order.status()).isEqualTo(200);
        String orderId = order.json("$.orderId");

        assertThat(http.post("/api/payments/online/verify", token, confirmBody(orderId, "pay_mock_1")).status()).isEqualTo(200);
        assertThat(http.post("/api/payments/online/verify", token, confirmBody(orderId, "pay_mock_1")).status()).as("the browser asks twice").isEqualTo(200);
        String webhook = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_mock_1\",\"order_id\":\"" + orderId + "\"}}}}";
        assertThat(http.post("/api/payments/online/webhook", null, webhook, "X-Razorpay-Signature", MOCK_SIGNATURE).status()).as("and the provider tells us as well").isEqualTo(200);

        assertThat(bd(http.get("/api/wallet", token).json("$.balance"))).isEqualByComparingTo("300.00");
        assertThat(count("select count(*) from ledger_transactions where idempotency_key = ?", "online-" + orderId)).isEqualTo(1);
    }

    @Test
    void aForgedPaymentConfirmationOrSomebodyElsesOrderIsRefused() {
        UUID rider = id();
        String token = TestTokens.token(rider, "RIDER");
        String orderId = http.post("/api/payments/online/orders", token, "{\"purpose\":\"TOPUP\",\"amount\":100}").json("$.orderId");

        assertThat(http.post("/api/payments/online/verify", token, "{\"orderId\":\"" + orderId + "\",\"paymentId\":\"pay_mock_x\",\"signature\":\"forged\"}").status()).isEqualTo(400);
        assertThat(http.post("/api/payments/online/verify", TestTokens.token(id(), "RIDER"), confirmBody(orderId, "pay_mock_x")).status()).as("not their order").isEqualTo(404);
        assertThat(http.post("/api/payments/online/webhook", null, "{}", "X-Razorpay-Signature", "forged").status()).isEqualTo(400);
        assertThat(http.post("/api/payments/online/webhook", null, "{}").status()).as("no signature at all").isEqualTo(400);
        assertThat(bd(http.get("/api/wallet", token).json("$.balance"))).isEqualByComparingTo("0.00");
        assertThat(http.post("/api/payments/online/orders", token, "{\"purpose\":\"TOPUP\",\"amount\":5}").status()).as("below the minimum").isEqualTo(400);
    }

    @Test
    void payingAFailedRideOnlineCreditsTheShortfallAndTheRidePaymentCompletes() {
        UUID rider = id();
        UUID driver = id();
        UUID ride = id();
        String token = TestTokens.token(rider, "RIDER");
        // The test card declines fares above 5000 and the wallet is empty: the payment fails
        Payment failed = payments.chargeForCompletedRide(ride, rider, driver, new BigDecimal("6000.00"), "INR", null);
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);

        Http.Response order = http.post("/api/payments/online/orders", token, "{\"purpose\":\"RIDE\",\"rideId\":\"" + ride + "\"}");
        assertThat(order.status()).isEqualTo(200);
        assertThat(bd(order.json("$.amount"))).as("exactly what the wallet lacks").isEqualByComparingTo("6000.00");
        assertThat(http.post("/api/payments/online/verify", token, confirmBody(order.json("$.orderId"), "pay_mock_r")).status()).isEqualTo(200);

        assertThat(statusOf(ride)).isEqualTo("SUCCEEDED");
        assertThat(count("select count(*) from payments where ride_id = ? and method = 'WALLET'", ride)).isEqualTo(1);
        assertThat(balance(accounts.walletOf(rider))).as("the wallet was filled and spent").isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ tips and receipts

    @Test
    void aRiderCanTipOnceAfterAPaidRideAndTheDriverGetsAllOfIt() {
        UUID rider = id();
        UUID driver = id();
        UUID ride = id();
        String token = TestTokens.token(rider, "RIDER");
        fundedWallet(rider, "500");
        paidRide(ride, rider, driver, "100.00");
        BigDecimal before = balance(accounts.earningsOf(driver));

        assertThat(http.post("/api/payments/rides/" + ride + "/tip", token, "{\"amount\":25}").status()).isEqualTo(200);
        assertThat(balance(accounts.earningsOf(driver)).subtract(before)).isEqualByComparingTo("25.00");
        assertThat(http.post("/api/payments/rides/" + ride + "/tip", token, "{\"amount\":25}").status()).as("one tip per ride").isEqualTo(409);
        assertThat(http.post("/api/payments/rides/" + id() + "/tip", token, "{\"amount\":25}").status()).isEqualTo(404);
        assertThat(http.post("/api/payments/rides/" + ride + "/tip", token, "{\"amount\":0.5}").status()).isEqualTo(400);
        assertThat(http.post("/api/payments/rides/" + ride + "/tip", TestTokens.token(driver, "DRIVER"), "{\"amount\":25}").status()).as("drivers cannot tip").isEqualTo(403);
    }

    @Test
    void aTipThatTheWalletCannotCoverIsRefusedAndLeavesNoTrace() {
        UUID rider = id();
        UUID ride = id();
        fundedWallet(rider, "100");
        paidRide(ride, rider, id(), "100.00");

        assertThat(http.post("/api/payments/rides/" + ride + "/tip", TestTokens.token(rider, "RIDER"), "{\"amount\":50}").status()).isEqualTo(400);
        assertThat(count("select count(*) from tips where ride_id = ?", ride)).as("the tip row rolled back with the refused payment").isZero();
    }

    @Test
    void theReceiptIsForTheRideSPartiesAndAnAdministratorOnly() {
        UUID rider = id();
        UUID driver = id();
        UUID ride = id();
        fundedWallet(rider, "300");
        paidRide(ride, rider, driver, "200.00");

        Http.Response receipt = http.get("/api/payments/rides/" + ride + "/receipt", TestTokens.token(rider, "RIDER"));
        assertThat(receipt.status()).isEqualTo(200);
        assertThat((String) receipt.json("$.number")).startsWith("RX-");
        assertThat(bd(receipt.json("$.paid"))).isEqualByComparingTo("200.00");
        assertThat((String) receipt.json("$.method")).isEqualTo("WALLET");
        assertThat(http.get("/api/payments/rides/" + ride + "/receipt", TestTokens.token(driver, "DRIVER")).status()).isEqualTo(200);
        assertThat(http.get("/api/payments/rides/" + ride + "/receipt", admin).status()).isEqualTo(200);
        assertThat(http.get("/api/payments/rides/" + ride + "/receipt", TestTokens.token(id(), "RIDER")).status()).as("a stranger").isEqualTo(404);
    }

    // ------------------------------------------------------------------ cancellation fee

    @Test
    void cancellingAfterTheDriverArrivedChargesTheFeeOnceAndSplitsItLikeAFare() {
        UUID rider = id();
        UUID driver = id();
        UUID ride = id();
        fundedWallet(rider, "100");
        BigDecimal revenueBefore = balance(AccountType.REVENUE_ID);

        assertThat(cancellationFees.onRiderCancelledAfterArrival(ride, rider, driver)).isTrue();
        assertThat(cancellationFees.onRiderCancelledAfterArrival(ride, rider, driver)).as("the same cancellation, handled twice").isFalse();

        assertThat(balance(accounts.walletOf(rider))).isEqualByComparingTo("70.00");
        assertThat(balance(accounts.earningsOf(driver))).isEqualByComparingTo("24.00");
        assertThat(balance(AccountType.REVENUE_ID).subtract(revenueBefore)).isEqualByComparingTo("6.00");
    }

    @Test
    void aRiderWhoseWalletCannotPayTheFeeIsNotChargedAndNotBlocked() {
        UUID rider = id();
        UUID ride = id();
        fundedWallet(rider, "10");

        assertThat(cancellationFees.onRiderCancelledAfterArrival(ride, rider, id())).isFalse();
        assertThat(balance(accounts.walletOf(rider))).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("select note from cancellation_fees where ride_id = ?", String.class, ride)).contains("Waived");
    }

    // ------------------------------------------------------------------ withdrawals

    private UUID driverWithEarnings(String fare) {
        UUID rider = id();
        UUID driver = id();
        fundedWallet(rider, "1000");
        paidRide(id(), rider, driver, fare);
        return driver;
    }

    @Test
    void aWithdrawalHoldsTheMoneyAndSettlesOrReturnsItWithoutEverCreatingMoney() {
        UUID driver = driverWithEarnings("500.00");   // 400.00 after the 20% commission
        String token = TestTokens.token(driver, "DRIVER");
        UUID earnings = accounts.earningsOf(driver);
        BigDecimal gatewayBefore = balance(AccountType.GATEWAY_ID);

        Http.Response request = http.post("/api/driver/payouts", token, "{\"amount\":300,\"upiId\":\"driver@okbank\"}");
        assertThat(request.status()).isEqualTo(201);
        String id = request.json("$.id");
        assertThat(balance(earnings)).as("set aside at once").isEqualByComparingTo("100.00");
        assertThat(http.post("/api/driver/payouts", token, "{\"amount\":150,\"upiId\":\"driver@okbank\"}").status()).as("more than is left").isEqualTo(400);

        assertThat(http.post("/api/admin/payouts/" + id + "/paid", admin, "{\"reference\":\"UTR123456\"}").status()).isEqualTo(200);
        assertThat(http.post("/api/admin/payouts/" + id + "/paid", admin, "{\"reference\":\"UTR123456\"}").status()).as("decided already").isEqualTo(409);
        assertThat(balance(AccountType.GATEWAY_ID).subtract(gatewayBefore)).as("the money left for the outside world").isEqualByComparingTo("300.00");
        assertThat(balance(AccountType.PAYOUT_HOLD_ID)).isEqualByComparingTo("0.00");

        String second = http.post("/api/driver/payouts", token, "{\"amount\":100,\"upiId\":\"driver@okbank\"}").json("$.id");
        assertThat(http.post("/api/admin/payouts/" + second + "/reject", admin, "{\"note\":\"Please check your UPI id\"}").status()).isEqualTo(200);
        assertThat(balance(earnings)).as("rejected: given back").isEqualByComparingTo("100.00");
        assertThat(ledger.sumOfAllBalances()).as("the books still add up to zero").isEqualByComparingTo("0.00");
    }

    @Test
    void aWithdrawalNeedsASensibleAmountAndUpiIdAndOnlyDriversMayAsk() {
        UUID driver = driverWithEarnings("500.00");
        String token = TestTokens.token(driver, "DRIVER");

        assertThat(http.post("/api/driver/payouts", token, "{\"amount\":50,\"upiId\":\"driver@okbank\"}").status()).as("below the minimum").isEqualTo(400);
        assertThat(http.post("/api/driver/payouts", token, "{\"amount\":200,\"upiId\":\"not a upi id\"}").status()).isEqualTo(400);
        assertThat(http.post("/api/driver/payouts", TestTokens.token(id(), "RIDER"), "{\"amount\":200,\"upiId\":\"a@b\"}").status()).isEqualTo(403);
        assertThat(http.get("/api/admin/payouts", token).status()).as("drivers cannot see the admin queue").isEqualTo(403);
        assertThat(http.get("/api/admin/payouts", admin).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ referrals

    @Test
    void aReferralRewardsBothSidesOnTheNewRiderSFirstPaidRideAndOnlyOnce() {
        UUID friend = id();
        UUID newRider = id();
        String friendToken = TestTokens.token(friend, "RIDER");
        String newToken = TestTokens.token(newRider, "RIDER");
        String code = http.get("/api/referrals/me", friendToken).json("$.code");
        assertThat(http.get("/api/referrals/me", friendToken).<String>json("$.code")).as("stable").isEqualTo(code);

        assertThat(http.post("/api/referrals/apply", friendToken, "{\"code\":\"" + code + "\"}").status()).as("not your own").isEqualTo(400);
        assertThat(http.post("/api/referrals/apply", newToken, "{\"code\":\"NOSUCHCODE\"}").status()).isEqualTo(400);
        assertThat(http.post("/api/referrals/apply", newToken, "{\"code\":\"" + code.toLowerCase() + "\"}").status()).as("case does not matter").isEqualTo(204);
        assertThat(http.post("/api/referrals/apply", newToken, "{\"code\":\"" + code + "\"}").status()).as("only once").isEqualTo(409);
        assertThat(balance(accounts.walletOf(newRider))).as("nothing at sign-up").isEqualByComparingTo("0.00");

        fundedWallet(newRider, "200");
        paidRide(id(), newRider, id(), "100.00");
        assertThat(balance(accounts.walletOf(newRider))).isEqualByComparingTo("150.00");   // 200 - 100 + 50 reward
        assertThat(balance(accounts.walletOf(friend))).isEqualByComparingTo("50.00");
        paidRide(id(), newRider, id(), "50.00");
        assertThat(balance(accounts.walletOf(friend))).as("the second ride rewards nothing").isEqualByComparingTo("50.00");
        assertThat(http.get("/api/referrals/me", friendToken).<Integer>json("$.rewarded")).isEqualTo(1);
    }

    @Test
    void anInviteCodeIsOnlyForRidersWhoHaveNotPaidForARideYet() {
        UUID friend = id();
        UUID veteran = id();
        String code = http.get("/api/referrals/me", TestTokens.token(friend, "RIDER")).json("$.code");
        fundedWallet(veteran, "300");
        paidRide(id(), veteran, id(), "100.00");

        assertThat(http.post("/api/referrals/apply", TestTokens.token(veteran, "RIDER"), "{\"code\":\"" + code + "\"}").status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ administrators

    @Test
    void anAdministratorCanCreditOrDebitButNeverBelowZeroAndTheBooksStillBalance() {
        UUID rider = id();

        Http.Response credit = http.post("/api/admin/ledger/adjustments", admin, "{\"userId\":\"" + rider + "\",\"kind\":\"WALLET\",\"amount\":80,\"reason\":\"Goodwill credit\"}");
        assertThat(credit.status()).isEqualTo(200);
        assertThat(balance(accounts.walletOf(rider))).isEqualByComparingTo("80.00");
        assertThat(http.post("/api/admin/ledger/adjustments", admin, "{\"userId\":\"" + rider + "\",\"kind\":\"WALLET\",\"amount\":-30,\"reason\":\"Fix\"}").status()).isEqualTo(200);
        assertThat(http.post("/api/admin/ledger/adjustments", admin, "{\"userId\":\"" + rider + "\",\"kind\":\"WALLET\",\"amount\":-500,\"reason\":\"Too much\"}").status()).isEqualTo(400);
        assertThat(http.post("/api/admin/ledger/adjustments", admin, "{\"userId\":\"" + rider + "\",\"kind\":\"BANK\",\"amount\":5,\"reason\":\"x\"}").status()).isEqualTo(400);
        assertThat(http.post("/api/admin/ledger/adjustments", TestTokens.token(rider, "RIDER"), "{\"userId\":\"" + rider + "\",\"kind\":\"WALLET\",\"amount\":999,\"reason\":\"me\"}").status())
                .as("a rider cannot give themselves money").isEqualTo(403);
        assertThat(balance(accounts.walletOf(rider))).isEqualByComparingTo("50.00");
        assertThat(ledger.sumOfAllBalances()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ offers and incentives

    @Test
    void ridersSeeTheListedOffersThatStillApplyToThem() {
        String listed = "LIST" + id().toString().substring(0, 6).toUpperCase();
        String hidden = "HIDE" + id().toString().substring(0, 6).toUpperCase();
        for (String code : List.of(listed, hidden)) {
            String body = "{\"code\":\"" + code + "\",\"discountType\":\"PERCENT\",\"value\":10,\"validFrom\":\"" + Instant.now().minus(1, ChronoUnit.HOURS)
                    + "\",\"validUntil\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\",\"perUserLimit\":1}";
            assertThat(http.post("/api/admin/coupons", admin, body).status()).isEqualTo(201);
        }
        String hiddenId = jdbc.queryForObject("select id::text from coupons where code = ?", String.class, hidden);
        assertThat(http.post("/api/admin/coupons/" + hiddenId + "/listed", admin, "{\"listed\":false}").status()).isEqualTo(204);

        Http.Response offers = http.get("/api/coupons/offers", TestTokens.token(id(), "RIDER"));
        assertThat(offers.status()).isEqualTo(200);
        assertThat(offers.body()).contains(listed).doesNotContain(hidden);
        assertThat(http.get("/api/coupons/offers", TestTokens.token(id(), "DRIVER")).status()).isEqualTo(403);
    }

    @Test
    void aDriverSeesHowFarTodayIsFromTheNextBonusTier() {
        UUID driver = driverWithEarnings("100.00");   // one trip

        Http.Response progress = http.get("/api/driver/incentives", TestTokens.token(driver, "DRIVER"));
        assertThat(progress.status()).isEqualTo(200);
        assertThat(progress.<Integer>json("$.tripsToday")).isEqualTo(1);
        assertThat(progress.<Integer>json("$.next.trips")).isEqualTo(10);
        assertThat(progress.<Integer>json("$.tripsToNext")).isEqualTo(9);
    }
}
