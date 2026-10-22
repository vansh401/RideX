package com.ridex.payment.extras;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.ConflictException;

/**
 * "Invite a friend": every user has a short code. A NEW rider applies a friend's code once, and after the new rider's first paid ride both
 * wallets are credited from the PROMO account (the company's marketing budget). Rewarding happens after a real paid ride, not at sign-up, so
 * creating accounts to farm rewards earns nothing.
 */
@Service
public class ReferralService {

    public record Summary(String code, BigDecimal reward, long invited, long rewarded, String appliedCode, boolean rewardPending) {
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O or 1/I: codes are read aloud and typed by hand
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final LedgerAccounts accounts;
    private final BigDecimal reward;

    public ReferralService(JdbcTemplate jdbc, LedgerService ledger, LedgerAccounts accounts,
                           @Value("${ridex.payments.referral-reward:50}") BigDecimal reward) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.reward = reward;
    }

    @Transactional
    public String codeOf(UUID userId) {
        List<String> existing = jdbc.queryForList("select code from referral_codes where user_id = ?", String.class, userId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder code = new StringBuilder(7);
            for (int i = 0; i < 7; i++) {
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            int inserted = jdbc.update("insert into referral_codes (user_id, code) values (?, ?) on conflict do nothing", userId, code.toString());
            if (inserted == 1) {
                return code.toString();
            }
            existing = jdbc.queryForList("select code from referral_codes where user_id = ?", String.class, userId);
            if (!existing.isEmpty()) {
                return existing.get(0);   // a parallel request created it
            }
        }
        throw new IllegalStateException("Could not create a referral code");
    }

    @Transactional
    public void apply(UUID refereeId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        List<UUID> owner = jdbc.queryForList("select user_id from referral_codes where code = ?", UUID.class, code);
        if (owner.isEmpty()) {
            throw new BadRequestException("This invite code does not exist");
        }
        if (owner.get(0).equals(refereeId)) {
            throw new BadRequestException("You cannot use your own invite code");
        }
        Integer rides = jdbc.queryForObject("select count(*) from payments where rider_user_id = ? and status = 'SUCCEEDED'", Integer.class, refereeId);
        if (rides != null && rides > 0) {
            throw new BadRequestException("Invite codes are only for riders who have not paid for a ride yet");
        }
        int inserted = jdbc.update("insert into referrals (referee_user_id, referrer_user_id, code) values (?, ?, ?) on conflict do nothing", refereeId, owner.get(0), code);
        if (inserted == 0) {
            throw new ConflictException("You already used an invite code");
        }
    }

    /** Called when a rider's payment succeeds. Rewards once, only the first time. */
    @Transactional
    public void rewardOnFirstPaidRide(UUID riderUserId) {
        List<UUID> referrer = jdbc.queryForList("select referrer_user_id from referrals where referee_user_id = ? and rewarded_at is null", UUID.class, riderUserId);
        if (referrer.isEmpty() || reward.signum() <= 0) {
            return;
        }
        LedgerService.Posting posting = ledger.post("REFERRAL", riderUserId.toString(), "referral-" + riderUserId, "Invite reward",
                List.of(new LedgerService.Leg(AccountType.PROMO_ID, reward.multiply(BigDecimal.valueOf(2)).negate()),
                        new LedgerService.Leg(accounts.walletOf(riderUserId), reward), new LedgerService.Leg(accounts.walletOf(referrer.get(0)), reward)));
        if (posting.done() || posting.status() == LedgerService.Status.DUPLICATE) {
            jdbc.update("update referrals set rewarded_at = ? where referee_user_id = ?", Timestamp.from(Instant.now()), riderUserId);
        }
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID userId, String code) {
        Long invited = jdbc.queryForObject("select count(*) from referrals where referrer_user_id = ?", Long.class, userId);
        Long rewarded = jdbc.queryForObject("select count(*) from referrals where referrer_user_id = ? and rewarded_at is not null", Long.class, userId);
        List<String> applied = jdbc.queryForList("select code from referrals where referee_user_id = ?", String.class, userId);
        List<Boolean> pending = jdbc.queryForList("select rewarded_at is null from referrals where referee_user_id = ?", Boolean.class, userId);
        return new Summary(code, reward, invited == null ? 0 : invited, rewarded == null ? 0 : rewarded, applied.isEmpty() ? null : applied.get(0),
                !pending.isEmpty() && pending.get(0));
    }
}
