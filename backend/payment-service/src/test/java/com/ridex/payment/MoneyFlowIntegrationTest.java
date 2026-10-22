package com.ridex.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ridex.payment.coupon.CouponService;
import com.ridex.payment.earnings.IncentiveService;
import com.ridex.payment.gateway.MockGateway;
import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.payment.refund.RefundService;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.testing.EventTestSupport;
import com.ridex.platform.testing.TestTokens;

/**
 * Phase 14, the money: wallet, wallet-first payment, coupons, refunds, earnings and incentives, against real PostgreSQL, Redis and Kafka.
 * The ledger's own guarantees are in LedgerIntegrationTest; here the flows that use it.
 */
class MoneyFlowIntegrationTest extends PaymentTestBase {

    private static final BigDecimal FARE = new BigDecimal("252.00");

    @Autowired
    private PaymentService payments;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private LedgerAccounts accounts;
    @Autowired
    private CouponService coupons;
    @Autowired
    private RefundService refunds;
    @Autowired
    private IncentiveService incentives;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Http http;
    private String admin;

    @BeforeEach
    void client() {
        http = new Http(port);
        admin = TestTokens.token(id(), "ADMIN");
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal bd(Object value) {
        return new BigDecimal(String.valueOf(value)).setScale(2);
    }

    private BigDecimal balance(UUID account) {
        return ledger.balanceOf(account);
    }

    private BigDecimal system(UUID id) {
        return ledger.balanceOf(id);
    }

    private UUID walletWith(UUID rider, String amount) {
        UUID wallet = accounts.walletOf(rider);
        if (new BigDecimal(amount).signum() > 0) {
            new TransactionTemplate(transactionManager).executeWithoutResult(s -> ledger.post("TOPUP", "test", "fund-" + wallet + "-" + id(), "test",
                    List.of(new LedgerService.Leg(AccountType.GATEWAY_ID, new BigDecimal(amount).negate()), new LedgerService.Leg(wallet, new BigDecimal(amount)))));
        }
        return wallet;
    }

    private Payment pay(UUID ride, UUID rider, UUID driver, BigDecimal fare, String coupon) {
        return payments.chargeForCompletedRide(ride, rider, driver, fare, "INR", coupon);
    }

    private String createCoupon(String code, String type, String value, String maxTotal, int perUser, String minFare) {
        String body = "{\"code\":\"" + code + "\",\"discountType\":\"" + type + "\",\"value\":" + value + ",\"minFare\":" + minFare
                + ",\"validFrom\":\"" + Instant.now().minus(1, ChronoUnit.HOURS) + "\",\"validUntil\":\"" + Instant.now().plus(1, ChronoUnit.DAYS)
                + "\",\"maxTotalUses\":" + maxTotal + ",\"perUserLimit\":" + perUser + "}";
        assertThat(http.post("/api/admin/coupons", admin, body).status()).isEqualTo(201);
        return code;
    }

    // ------------------------------------------------------------------ the wallet

    @Test
    void aTopUpFillsTheWalletAndRepeatingTheSameKeyNeitherChargesNorCreditsTwice() {
        UUID rider = id();
        String token = TestTokens.token(rider, "RIDER");
        String key = "key-" + id();

        Http.Response first = http.post("/api/wallet/topups", token, "{\"amount\":500}", "Idempotency-Key", key);
        Http.Response again = http.post("/api/wallet/topups", token, "{\"amount\":500}", "Idempotency-Key", key);

        assertThat(first.status()).isEqualTo(200);
        assertThat(bd(first.json("$.balance"))).isEqualByComparingTo("500.00");
        assertThat(again.status()).isEqualTo(200);
        assertThat(bd(again.json("$.balance"))).as("the second press changed nothing").isEqualByComparingTo("500.00");
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key = ?", "topup-" + rider + "-" + key)).isEqualTo(1);
        Http.Response statement = http.get("/api/wallet/transactions", token);
        assertThat((int) statement.json("$.totalElements")).isEqualTo(1);
        assertThat((String) statement.json("$.content[0].type")).isEqualTo("TOPUP");
    }

    @Test
    void aTopUpMustBeReasonableHaveAKeyAndBeApprovedByTheCard() {
        String token = TestTokens.token(id(), "RIDER");

        assertThat(http.post("/api/wallet/topups", token, "{\"amount\":5}", "Idempotency-Key", "k" + id()).status()).as("below the minimum").isEqualTo(400);
        assertThat(http.post("/api/wallet/topups", token, "{\"amount\":20000}", "Idempotency-Key", "k" + id()).status()).as("above the maximum").isEqualTo(400);
        assertThat(http.post("/api/wallet/topups", token, "{\"amount\":100}").status()).as("no Idempotency-Key").isEqualTo(400);
        assertThat(http.post("/api/wallet/topups", token, "{\"amount\":6000}", "Idempotency-Key", "k" + id()).status()).as("declined by the test card").isEqualTo(400);
        assertThat(bd(http.get("/api/wallet", token).json("$.balance"))).isEqualByComparingTo("0.00");
    }

    @Test
    void whenTheCardCompanyDoesNotAnswerTheTopUpIsAnHonest503AndTheSameKeyWorksLater() {
        UUID rider = id();
        String token = TestTokens.token(rider, "RIDER");
        String key = "k" + id();

        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.ERROR, 0));
        Http.Response down = http.post("/api/wallet/topups", token, "{\"amount\":200}", "Idempotency-Key", key);
        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.NORMAL, 0));
        circuitBreaker.reset();
        Http.Response later = http.post("/api/wallet/topups", token, "{\"amount\":200}", "Idempotency-Key", key);

        assertThat(down.status()).isEqualTo(503);
        assertThat(later.status()).isEqualTo(200);
        assertThat(bd(later.json("$.balance"))).isEqualByComparingTo("200.00");
    }

    @Test
    void onlyRidersHaveAWallet() {
        assertThat(http.get("/api/wallet", TestTokens.token(id(), "DRIVER")).status()).isEqualTo(403);
        assertThat(http.get("/api/wallet", null).status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ paying for a ride

    @Test
    void aRiderWithMoneyInTheWalletPaysFromItAndTheLedgerSharesTheFare() {
        UUID ride = id(), rider = id(), driver = id();
        UUID wallet = walletWith(rider, "500.00");
        BigDecimal revenueBefore = system(AccountType.REVENUE_ID);

        Payment payment = pay(ride, rider, driver, FARE, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getMethod()).isEqualTo("WALLET");
        assertThat(balance(wallet)).isEqualByComparingTo("248.00");
        assertThat(balance(accounts.earningsOf(driver))).as("the fare minus 20 % commission").isEqualByComparingTo("201.60");
        assertThat(system(AccountType.REVENUE_ID).subtract(revenueBefore)).isEqualByComparingTo("50.40");
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key = ?", payment.getIdempotencyKey())).as("no card involved").isZero();
        assertThat(ledger.sumOfAllBalances()).isEqualByComparingTo("0");
    }

    @Test
    void withoutEnoughMoneyInTheWalletTheCardPaysAndTheDriverIsStillCredited() {
        UUID ride = id(), rider = id(), driver = id();
        UUID wallet = walletWith(rider, "100.00");   // less than the fare

        Payment payment = pay(ride, rider, driver, FARE, null);

        assertThat(payment.getMethod()).isEqualTo("GATEWAY");
        assertThat(balance(wallet)).as("the wallet was not touched").isEqualByComparingTo("100.00");
        assertThat(balance(accounts.earningsOf(driver))).isEqualByComparingTo("201.60");
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key = ?", payment.getIdempotencyKey())).isEqualTo(1);
    }

    @Test
    void payingTheSameRideAgainChangesNothing() {
        UUID ride = id(), rider = id(), driver = id();
        walletWith(rider, "500.00");

        pay(ride, rider, driver, FARE, null);
        pay(ride, rider, driver, FARE, null);

        assertThat(balance(accounts.earningsOf(driver))).isEqualByComparingTo("201.60");
        assertThat(count("select count(*) from ledger_transactions where idempotency_key = ?", "ride-payment-" + ride)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ coupons in the payment

    @Test
    void aCouponReducesWhatTheRiderPaysAndTheCompanyPaysTheDifferenceNotTheDriver() {
        UUID ride = id(), rider = id(), driver = id();
        String code = createCoupon("FLAT50" + System.nanoTime() % 100000, "FLAT", "50", "null", 1, "100");
        BigDecimal promoBefore = system(AccountType.PROMO_ID);

        Payment payment = pay(ride, rider, driver, FARE, code);

        assertThat(payment.getDiscount()).isEqualByComparingTo("50.00");
        assertThat(payment.netAmount()).isEqualByComparingTo("202.00");
        assertThat(jdbc.queryForObject("select amount from mock_gateway_charges where idempotency_key = ?", BigDecimal.class, payment.getIdempotencyKey()))
                .as("the card is charged the net amount").isEqualByComparingTo("202.00");
        assertThat(balance(accounts.earningsOf(driver))).as("the driver is paid on the full fare").isEqualByComparingTo("201.60");
        assertThat(promoBefore.subtract(system(AccountType.PROMO_ID))).as("the company funds the coupon").isEqualByComparingTo("50.00");
        assertThat(ledger.sumOfAllBalances()).isEqualByComparingTo("0");
    }

    @Test
    void aCouponThatDoesNotApplyNeverBlocksThePayment() {
        UUID ride = id(), rider = id(), driver = id();

        Payment unknown = pay(ride, rider, driver, FARE, "NO-SUCH-COUPON");

        assertThat(unknown.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(unknown.getDiscount()).isEqualByComparingTo("0");
    }

    @Test
    void aCouponCoveringTheWholeFareChargesNobody() {
        UUID ride = id(), rider = id(), driver = id();
        String code = createCoupon("FREE" + System.nanoTime() % 100000, "PERCENT", "100", "null", 1, "0");

        Payment payment = pay(ride, rider, driver, FARE, code);

        assertThat(payment.getMethod()).isEqualTo("PROMO");
        assertThat(payment.netAmount()).isEqualByComparingTo("0");
        assertThat(count("select count(*) from mock_gateway_charges where idempotency_key = ?", payment.getIdempotencyKey())).isZero();
        assertThat(balance(accounts.earningsOf(driver))).isEqualByComparingTo("201.60");
    }

    @Test
    void theCouponArrivesWithTheCompletedRideEventAndIsRedeemedOnce() {
        UUID ride = id(), rider = id(), driver = id();
        String code = createCoupon("EVT" + System.nanoTime() % 100000, "FLAT", "30", "null", 1, "0");

        publish(Topics.RIDE_EVENTS, ride.toString(), EventTestSupport.rideEventWithCoupon(id(), ride, rider, driver, "RIDE_STARTED", "RIDE_COMPLETED", "DRIVER", FARE, code));

        assertThat(awaitStatus(ride, "SUCCEEDED", 40)).isTrue();
        assertThat(jdbc.queryForObject("select discount from payments where ride_id = ?", BigDecimal.class, ride)).isEqualByComparingTo("30.00");
        assertThat(count("select count(*) from coupon_redemptions where ride_id = ?", ride)).isEqualTo(1);
    }

    @Test
    void aCouponWithFiveUsesIsUsedByExactlyFiveOfTwentyRidersRedeemingAtTheSameTime() throws Exception {
        String code = createCoupon("FIVE" + System.nanoTime() % 100000, "FLAT", "20", "5", 1, "0");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<CouponService.Evaluation>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID rider = id(), ride = id();
            results.add(pool.submit(() -> coupons.redeem(code, rider, ride, FARE)));
        }
        int eligible = 0;
        for (Future<CouponService.Evaluation> result : results) {
            if (result.get(60, TimeUnit.SECONDS).eligible()) {
                eligible++;
            }
        }
        pool.shutdown();

        assertThat(eligible).as("exactly the five uses that exist").isEqualTo(5);
        assertThat(count("select used_count from coupons where code = ?", code)).isEqualTo(5);
        assertThat(count("select count(*) from coupon_redemptions r join coupons c on c.id = r.coupon_id where c.code = ?", code)).isEqualTo(5);
    }

    @Test
    void oneRiderCannotUseAOncePerRiderCouponOnTenRidesAtTheSameTime() throws Exception {
        String code = createCoupon("ONCE" + System.nanoTime() % 100000, "PERCENT", "10", "null", 1, "0");
        UUID rider = id();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<CouponService.Evaluation>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID ride = id();
            results.add(pool.submit(() -> coupons.redeem(code, rider, ride, FARE)));
        }
        int eligible = 0;
        for (Future<CouponService.Evaluation> result : results) {
            if (result.get(60, TimeUnit.SECONDS).eligible()) {
                eligible++;
            }
        }
        pool.shutdown();

        assertThat(eligible).isEqualTo(1);
    }

    @Test
    void aPreviewTellsTheRiderWhatTheCouponGivesWithoutUsingItUp() {
        String code = createCoupon("PREV" + System.nanoTime() % 100000, "PERCENT", "20", "null", 1, "100");
        String token = TestTokens.token(id(), "RIDER");

        Http.Response ok = http.get("/api/coupons/" + code.toLowerCase() + "/preview?fare=252.00", token);
        Http.Response tooLow = http.get("/api/coupons/" + code + "/preview?fare=50.00", token);
        Http.Response unknown = http.get("/api/coupons/NOPE/preview?fare=252.00", token);

        assertThat((boolean) ok.json("$.eligible")).isTrue();
        assertThat(bd(ok.json("$.discount"))).isEqualByComparingTo("50.40");
        assertThat((String) tooLow.json("$.reason")).isEqualTo("FARE_TOO_LOW");
        assertThat((String) unknown.json("$.reason")).isEqualTo("UNKNOWN");
        assertThat(count("select used_count from coupons where code = ?", code)).as("a preview uses nothing up").isZero();
        assertThat(http.get("/api/coupons/" + code + "/preview?fare=252", TestTokens.token(id(), "DRIVER")).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ refunds

    private Http.Response refund(UUID ride, String amount, String destination, String key) {
        return http.post("/api/admin/refunds", admin, "{\"rideId\":\"" + ride + "\"" + (amount == null ? "" : ",\"amount\":" + amount)
                + ",\"reason\":\"test\",\"destination\":\"" + destination + "\"}", "Idempotency-Key", key);
    }

    @Test
    void aWalletRefundReturnsMoneyAtOnceAndNeverMoreThanWasPaid() {
        UUID ride = id(), rider = id(), driver = id();
        pay(ride, rider, driver, FARE, null);   // empty wallet: paid by card, 252.00

        assertThat(refund(ride, "100.00", "WALLET", "r1-" + ride).status()).isEqualTo(201);
        assertThat(balance(accounts.walletOf(rider))).isEqualByComparingTo("100.00");
        assertThat(refund(ride, "200.00", "WALLET", "r2-" + ride).status()).as("only 152.00 is left to refund").isEqualTo(409);
        assertThat(refund(ride, "152.00", "WALLET", "r3-" + ride).status()).isEqualTo(201);
        assertThat(refund(ride, "0.01", "WALLET", "r4-" + ride).status()).as("everything paid has been returned").isEqualTo(409);
        assertThat(balance(accounts.walletOf(rider))).isEqualByComparingTo("252.00");
        assertThat(jdbc.queryForObject("select refunded_amount from payments where ride_id = ?", BigDecimal.class, ride)).isEqualByComparingTo("252.00");
        assertThat(ledger.sumOfAllBalances()).isEqualByComparingTo("0");
    }

    @Test
    void theSameRefundRequestTwiceRefundsOnce() {
        UUID ride = id(), rider = id(), driver = id();
        pay(ride, rider, driver, FARE, null);
        String key = "dup-" + ride;

        Http.Response first = refund(ride, "50.00", "WALLET", key);
        Http.Response second = refund(ride, "50.00", "WALLET", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat((String) second.json("$.id")).isEqualTo(first.json("$.id"));
        assertThat(balance(accounts.walletOf(rider))).isEqualByComparingTo("50.00");
        assertThat(count("select count(*) from refunds where ride_id = ?", ride)).isEqualTo(1);
    }

    @Test
    void aRefundToTheCardWaitsWhileTheCardCompanyIsDownAndCompletesWhenItIsBack() {
        UUID ride = id(), rider = id(), driver = id();
        pay(ride, rider, driver, FARE, null);   // by card
        BigDecimal revenueBefore = system(AccountType.REVENUE_ID);

        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.ERROR, 0));
        Http.Response waiting = refund(ride, "100.00", "ORIGINAL", "card-" + ride);
        assertThat((String) waiting.json("$.status")).as("not known to have worked: stays pending").isEqualTo("PENDING");
        assertThat(system(AccountType.REVENUE_ID)).as("nothing booked yet").isEqualByComparingTo(revenueBefore);

        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.NORMAL, 0));
        circuitBreaker.reset();
        refunds.retryOne(UUID.fromString(waiting.json("$.id")));

        assertThat(jdbc.queryForObject("select status from refunds where id = ?::uuid", String.class, waiting.json("$.id").toString())).isEqualTo("SUCCEEDED");
        assertThat(count("select count(*) from mock_gateway_refunds where idempotency_key = ?", "refund-" + waiting.json("$.id"))).isEqualTo(1);
        assertThat(revenueBefore.subtract(system(AccountType.REVENUE_ID))).isEqualByComparingTo("100.00");
    }

    @Test
    void aRideThatWasPaidFromTheWalletIsRefundedToTheWalletEvenIfTheCardWasAsked() {
        UUID ride = id(), rider = id(), driver = id();
        walletWith(rider, "300.00");
        pay(ride, rider, driver, FARE, null);   // wallet: 48.00 left

        Http.Response response = refund(ride, "40.00", "ORIGINAL", "w-" + ride);

        assertThat((String) response.json("$.destination")).isEqualTo("WALLET");
        assertThat(balance(accounts.walletOf(rider))).isEqualByComparingTo("88.00");
    }

    @Test
    void aResolvedComplaintWithARefundRefundsTheRiderThroughTheEventAndOnlyOnce() {
        UUID ride = id(), rider = id(), driver = id(), complaint = id();
        pay(ride, rider, driver, FARE, null);

        publish(Topics.COMPLAINT_EVENTS, ride.toString(), EventTestSupport.complaintEvent(id(), "complaint.resolved", complaint, ride, rider, "OVERCHARGED", "RESOLVED", new BigDecimal("80.00")));
        publish(Topics.COMPLAINT_EVENTS, ride.toString(), EventTestSupport.complaintEvent(id(), "complaint.resolved", complaint, ride, rider, "OVERCHARGED", "RESOLVED", new BigDecimal("80.00")));   // a different event, same complaint

        assertThat(EventTestSupport.awaitTrue(() -> balance(accounts.walletOf(rider)).compareTo(new BigDecimal("80.00")) == 0, 40)).isTrue();
        EventTestSupport.awaitTrue(() -> false, 3);
        assertThat(balance(accounts.walletOf(rider))).as("one complaint, one refund").isEqualByComparingTo("80.00");
        assertThat(count("select count(*) from refunds where source = 'COMPLAINT' and source_ref = ?", complaint.toString())).isEqualTo(1);
    }

    @Test
    void aComplaintResolvedWithoutMoneyRefundsNothing() {
        UUID ride = id(), rider = id(), driver = id();
        pay(ride, rider, driver, FARE, null);

        publish(Topics.COMPLAINT_EVENTS, ride.toString(), EventTestSupport.complaintEvent(id(), "complaint.resolved", id(), ride, rider, "RUDE_DRIVER", "RESOLVED", null));
        publish(Topics.COMPLAINT_EVENTS, ride.toString(), EventTestSupport.complaintEvent(id(), "complaint.filed", id(), ride, rider, "RUDE_DRIVER", "OPEN", null));

        EventTestSupport.awaitTrue(() -> false, 4);
        assertThat(count("select count(*) from refunds where ride_id = ?", ride)).isZero();
    }

    // ------------------------------------------------------------------ driver earnings and incentives

    @Test
    void theDriverSeesTheirEarningsFromTheLedgerAndOthersSeeNothingOfThem() {
        UUID driver = id(), rider = id();
        pay(id(), rider, driver, FARE, null);
        pay(id(), rider, driver, FARE, null);
        String token = TestTokens.token(driver, "DRIVER");

        Http.Response response = http.get("/api/driver/earnings", token);

        assertThat(response.status()).isEqualTo(200);
        assertThat(bd(response.json("$.today"))).isEqualByComparingTo("403.20");
        assertThat((int) response.json("$.tripsToday")).isEqualTo(2);
        assertThat(bd(response.json("$.balance"))).isEqualByComparingTo("403.20");
        assertThat((List<?>) response.json("$.daily")).hasSize(30);
        assertThat((List<?>) response.json("$.recent")).hasSize(2);
        // another driver has their own (empty) dashboard; a rider has none
        assertThat(bd(http.get("/api/driver/earnings", TestTokens.token(id(), "DRIVER")).json("$.today"))).isEqualByComparingTo("0.00");
        assertThat(http.get("/api/driver/earnings", TestTokens.token(rider, "RIDER")).status()).isEqualTo(403);
    }

    @Test
    void theEarningsAreCachedAndANewPaymentThrowsTheCachedCopyAway() {
        UUID driver = id(), rider = id();
        String token = TestTokens.token(driver, "DRIVER");
        pay(id(), rider, driver, FARE, null);
        String key = "earnings:summary:" + driver;
        redis.delete(key);

        http.get("/api/driver/earnings", token);
        assertThat(redis.hasKey(key)).as("the first call fills the cache").isTrue();

        pay(id(), rider, driver, FARE, null);   // a new payment commits...
        assertThat(redis.hasKey(key)).as("...and the stale copy is gone").isFalse();
        assertThat((int) http.get("/api/driver/earnings", token).json("$.tripsToday")).isEqualTo(2);
    }

    @Test
    void aDriverWhoReachesTheTripTargetGetsTheBonusOnceHoweverOftenTheJobRuns() {
        UUID driver = id(), rider = id();
        for (int i = 0; i < 10; i++) {
            pay(id(), rider, driver, new BigDecimal("100.00"), null);
        }
        java.time.LocalDate today = Instant.now().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
        BigDecimal before = balance(accounts.earningsOf(driver));

        IncentiveService.Result first = incentives.payFor(today);
        IncentiveService.Result second = incentives.payFor(today);

        assertThat(first.driversPaid()).isGreaterThanOrEqualTo(1);
        assertThat(balance(accounts.earningsOf(driver)).subtract(before)).as("the 10-trip tier pays 100, once").isEqualByComparingTo("100.00");
        assertThat(second.driversPaid()).as("the second run pays nobody again").isZero();
        assertThat(bd(http.get("/api/driver/earnings", TestTokens.token(driver, "DRIVER")).json("$.bonuses30Days"))).isEqualByComparingTo("100.00");
        assertThat(ledger.sumOfAllBalances()).isEqualByComparingTo("0");
    }

    @Test
    void theFinanceViewShowsWhereTheMoneyIsAndAnIntactLedger() {
        pay(id(), id(), id(), FARE, null);

        Http.Response summary = http.get("/api/admin/ledger/summary", admin);
        Http.Response integrity = http.get("/api/admin/ledger/integrity", admin);

        assertThat(summary.status()).isEqualTo(200);
        assertThat(bd(summary.json("$.REVENUE")).signum()).isGreaterThanOrEqualTo(0);
        assertThat((boolean) integrity.json("$.ok")).isTrue();
        assertThat(http.get("/api/admin/ledger/integrity", TestTokens.token(id(), "RIDER")).status()).isEqualTo(403);
    }
}
