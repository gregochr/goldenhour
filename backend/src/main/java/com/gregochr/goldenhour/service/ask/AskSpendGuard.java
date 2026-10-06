package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether typed questions may spend money right now (plan §2.5 step 7): not when today's typed spend
 * has reached {@code photocast.ask.daily-spend-cap-usd}, and not while a paid model call's cost is
 * unrecorded ({@link AskJobRunService#accountingAvailable()}'s latch), because every further call
 * would be spend nobody can see.
 *
 * <p><b>Fails closed.</b> If today's spend cannot be read, the cap is treated as reached.
 *
 * <p><b>The cap is checked, not reserved.</b> The check and the question's engine run are separate
 * steps (the allowance is reserved between them, and the spend figure is memoised for up to 30
 * seconds), so the cap is a soft ceiling: requests that all pass it just before it is crossed can
 * together overshoot it. The overshoot is bounded — each user is limited to 5 requests a minute and a
 * daily allowance, only 4 model calls run at once (the {@code ask} bulkhead), and a conversation is at
 * most 4 turns — and it is closed from the other side by the per-call accounting latch inside the
 * engine. {@code AskService} re-checks at the last moment, after reserving and before the engine
 * runs.
 *
 * <p>Reaching the cap alerts the admins once per UK day (an in-memory latch, so a restart on the same
 * day may alert once more) and logs every refusal at WARN.
 */
@Component
public class AskSpendGuard {

    private static final Logger LOG = LoggerFactory.getLogger(AskSpendGuard.class);

    private final AskProperties properties;
    private final AskJobRunService jobRuns;
    private final AdminAlertService adminAlerts;
    private final Clock clock;
    private final AtomicReference<LocalDate> alertedOn = new AtomicReference<>();

    /**
     * Creates the guard.
     *
     * @param properties  the Ask settings (the cap)
     * @param jobRuns     today's typed spend and the accounting latch
     * @param adminAlerts mails the admins when the cap is reached
     * @param clock       the application clock (the UK civil day)
     */
    public AskSpendGuard(AskProperties properties, AskJobRunService jobRuns,
            AdminAlertService adminAlerts, Clock clock) {
        this.properties = properties;
        this.jobRuns = jobRuns;
        this.adminAlerts = adminAlerts;
        this.clock = clock;
    }

    /**
     * Whether typed questions are available: Ask is on, today's cap is not reached and every paid
     * call is on record. Side-effect free (no alert), for {@code GET /api/user/settings/ask}.
     *
     * @return true when a typed question may be sent to the engine
     */
    public boolean typedAvailable() {
        return properties.isEnabled() && jobRuns.accountingAvailable() && !capReached();
    }

    /**
     * Whether a typed question must be refused now, and if so why. Logs every refusal at WARN and,
     * the first time the cap is the reason on a UK day, alerts the admins.
     *
     * @return true when the question must be refused with 503 {@code TYPED_UNAVAILABLE}
     */
    public boolean refuse() {
        if (!jobRuns.accountingAvailable()) {
            LOG.warn("[ASK] Typed question refused: a model call's cost could not be recorded");
            return true;
        }
        long cap = properties.dailySpendCapMicroDollars();
        long spent;
        try {
            spent = jobRuns.typedSpendTodayMicroDollars();
        } catch (RuntimeException e) {
            LOG.error("[ASK] Typed question refused: today's spend could not be read: {}", e.toString());
            return true;
        }
        if (spent < cap) {
            return false;
        }
        LOG.warn("[ASK] Typed question refused: today's typed spend {} micro-dollars has reached the cap {}",
                spent, cap);
        alertOncePerDay(spent, cap);
        return true;
    }

    private boolean capReached() {
        try {
            return jobRuns.typedSpendTodayMicroDollars() >= properties.dailySpendCapMicroDollars();
        } catch (RuntimeException e) {
            LOG.error("[ASK] Today's spend could not be read; typed questions reported unavailable: {}",
                    e.toString());
            return true;
        }
    }

    private void alertOncePerDay(long spent, long cap) {
        LocalDate today = ForecastHorizon.today(clock);
        LocalDate previous = alertedOn.get();
        if (today.equals(previous) || !alertedOn.compareAndSet(previous, today)) {
            return;
        }
        try {
            adminAlerts.sendAskSpendCapAlert(today, spent, cap);
        } catch (RuntimeException e) {
            LOG.warn("[ASK] The spend-cap alert could not be sent: {}", e.toString());
        }
    }
}
