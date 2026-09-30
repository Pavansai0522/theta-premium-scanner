package com.premiumscanner.web;

import com.premiumscanner.config.ScannerProperties;
import com.premiumscanner.domain.RejectionReason;
import com.premiumscanner.domain.ScoreBreakdown;
import com.premiumscanner.domain.StrangleCandidate;
import com.premiumscanner.domain.StrangleMetrics;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Null-safe formatting and small view models for the Thymeleaf template (exposed as bean "fmt"). */
@Component("fmt")
public class ViewHelper {

    private static final String NONE = "—";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a 'ET'", Locale.US);

    private final ScannerProperties props;

    public ViewHelper(ScannerProperties props) {
        this.props = props;
    }

    public record LadderMark(String label, String value, double topPct, String kind) {
    }

    public record Ladder(List<LadderMark> marks, double zoneTop, double zoneHeight) {
    }

    public record Factor(String label, int pct, String weight) {
    }

    public record RejectionRow(String label, int calls, int puts) {
    }

    /** Two decimals: 247.60 */
    public String price(BigDecimal v) {
        return v == null ? NONE : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Keeps meaningful precision (0.675, 624.325) but never fewer than two decimals. */
    public String precise(BigDecimal v) {
        if (v == null) return NONE;
        BigDecimal s = v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        if (s.scale() < 2) s = s.setScale(2, RoundingMode.HALF_UP);
        return s.toPlainString();
    }

    public String usd(BigDecimal v) {
        return v == null ? NONE : "$" + precise(v);
    }

    public String strike(BigDecimal v) {
        if (v == null) return NONE;
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) s = s.setScale(0);
        return "$" + s.toPlainString();
    }

    public String theta(Double v) {
        return v == null ? NONE : String.format(Locale.US, "%.4f", v);
    }

    public String delta(Double v) {
        return v == null ? NONE : String.format(Locale.US, "%.3f", v);
    }

    public String greek(Double v) {
        return v == null ? NONE : String.format(Locale.US, "%.4f", v);
    }

    public String pct(Double fraction) {
        return fraction == null ? NONE : String.format(Locale.US, "%.2f%%", fraction * 100);
    }

    public String iv(Double v) {
        return v == null ? NONE : String.format(Locale.US, "%.1f%%", v * 100);
    }

    public String dollars(Double v) {
        return v == null ? NONE : String.format(Locale.US, "$%,.2f", v);
    }

    public String number(Double v) {
        return v == null ? NONE : String.format(Locale.US, "%.2f", v);
    }

    public String count(Long v) {
        return v == null ? NONE : String.format(Locale.US, "%,d", v);
    }

    public String score(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    public String longDate(LocalDate d) {
        return d == null ? NONE : d.format(LONG_DATE);
    }

    public String shortDate(LocalDate d) {
        return d == null ? NONE : d.format(SHORT_DATE);
    }

    public String time(Instant t) {
        return t == null ? "unknown time" : t.atZone(ET).format(TIME);
    }

    public String windowLabel(Double fraction) {
        if (fraction == null || fraction <= 0) return "all strikes";
        return String.format(Locale.US, "strikes within %.0f%% of spot", fraction * 100);
    }

    public List<RejectionRow> rejections(Map<RejectionReason, Integer> calls, Map<RejectionReason, Integer> puts) {
        List<RejectionRow> rows = new ArrayList<>();
        for (RejectionReason r : RejectionReason.values()) {
            int c = calls == null ? 0 : calls.getOrDefault(r, 0);
            int p = puts == null ? 0 : puts.getOrDefault(r, 0);
            if (c + p > 0) rows.add(new RejectionRow(r.label(), c, p));
        }
        return rows;
    }

    public List<Factor> factors(StrangleCandidate c) {
        ScoreBreakdown b = c.scoreBreakdown();
        ScannerProperties.Weights w = props.weights();
        double total = w.total() <= 0 ? 1 : w.total();
        return List.of(
                factor("Premium", b.premium(), w.premium(), total),
                factor("Theta decay", b.theta(), w.theta(), total),
                factor("Delta safety", b.delta(), w.delta(), total),
                factor("Breakeven cushion", b.cushion(), w.cushion(), total),
                factor("Liquidity", b.liquidity(), w.liquidity(), total),
                factor("DTE fit", b.dteFit(), w.dteFit(), total),
                factor("Delta balance", b.balance(), w.balance(), total));
    }

    private static Factor factor(String label, double value, double weight, double total) {
        return new Factor(label, (int) Math.round(value * 100), String.format(Locale.US, "%.0f%%", 100 * weight / total));
    }

    /** Vertical price ladder: strikes on the left, breakevens on the right, spot and expected move in between. */
    public Ladder ladder(StrangleCandidate c) {
        StrangleMetrics m = c.metrics();
        double spot = m.underlyingPrice().doubleValue();
        double callStrike = c.shortCall().strike().doubleValue();
        double putStrike = c.shortPut().strike().doubleValue();
        double upper = m.upperBreakeven().doubleValue();
        double lower = m.lowerBreakeven().doubleValue();
        Double em = m.expectedMove();

        double hi = Math.max(upper, em == null ? upper : spot + em);
        double lo = Math.min(lower, em == null ? lower : spot - em);
        double pad = Math.max((hi - lo) * 0.08, spot * 0.001);
        double top = hi + pad;
        double range = top - (lo - pad);

        List<LadderMark> marks = new ArrayList<>();
        marks.add(mark("Call strike", strike(c.shortCall().strike()), callStrike, top, range, "strike"));
        marks.add(mark("Upper breakeven", precise(m.upperBreakeven()), upper, top, range, "breakeven"));
        if (em != null) {
            marks.add(mark("+1 expected move", String.format(Locale.US, "%.2f", spot + em), spot + em, top, range, "em"));
            marks.add(mark("-1 expected move", String.format(Locale.US, "%.2f", spot - em), spot - em, top, range, "em"));
        }
        marks.add(mark("Spot", price(m.underlyingPrice()), spot, top, range, "spot"));
        marks.add(mark("Lower breakeven", precise(m.lowerBreakeven()), lower, top, range, "breakeven"));
        marks.add(mark("Put strike", strike(c.shortPut().strike()), putStrike, top, range, "strike"));

        double zoneTop = position(upper, top, range);
        double zoneBottom = position(lower, top, range);
        return new Ladder(marks, zoneTop, round2(zoneBottom - zoneTop));
    }

    private static LadderMark mark(String label, String value, double price, double top, double range, String kind) {
        return new LadderMark(label, value, position(price, top, range), kind);
    }

    private static double position(double price, double top, double range) {
        return round2(Math.max(0, Math.min(100, (top - price) / range * 100)));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
