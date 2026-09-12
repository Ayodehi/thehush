package com.ayodehi.thehush.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Counts every API call: tokens in, out, cached, and an estimated cost, per model. Two ledgers: the
 * session (since this world was opened) and the campaign (persisted to &lt;world&gt;/thehush/usage.json,
 * cleared with the campaign). Calls arrive from virtual threads, so everything is synchronized.
 */
public final class UsageMeter {
    private static final Logger LOGGER = LoggerFactory.getLogger("thehush.usage");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final UsageMeter INSTANCE = new UsageMeter();

    /** One ledger: totals and a per-model breakdown. */
    public static final class Ledger {
        public long calls;
        public long input;
        public long output;
        public long cacheRead;
        public long cacheWrite;
        public double cost;
        public long voiceLines;
        public long voiceChars;
        public double voiceCost;
        public Map<String, Ledger> byModel = new LinkedHashMap<>();

        void add(String model, LlmResponse.Usage u, double usd, boolean breakdown) {
            calls++;
            input += u.inputTokens();
            output += u.outputTokens();
            cacheRead += u.cacheReadTokens();
            cacheWrite += u.cacheWriteTokens();
            cost += usd;
            if (breakdown) {
                if (byModel == null) byModel = new LinkedHashMap<>();
                byModel.computeIfAbsent(model, k -> new Ledger()).add(model, u, usd, false);
            }
        }

        public long promptTokens() {
            return input + cacheRead + cacheWrite;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(calls).append(" call").append(calls == 1 ? "" : "s")
              .append(", ").append(fmt(promptTokens())).append(" prompt tokens (")
              .append(fmt(cacheRead)).append(" from cache, ").append(fmt(cacheWrite)).append(" written to it), ")
              .append(fmt(output)).append(" output, about ").append(Pricing.dollars(cost));
            if (byModel != null && byModel.size() > 1) {
                for (var e : byModel.entrySet()) {
                    sb.append("\n    ").append(e.getKey()).append(": ").append(e.getValue().calls).append(" calls, ")
                      .append(Pricing.dollars(e.getValue().cost));
                }
            }
            return sb.toString();
        }

        private static String fmt(long n) {
            if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
            if (n >= 10_000) return String.format("%.1fk", n / 1000.0);
            return Long.toString(n);
        }
    }

    private Ledger session = new Ledger();
    private Ledger campaign = new Ledger();
    private @Nullable Path file;
    private DoubleSupplier inputPrice = () -> 0;
    private DoubleSupplier outputPrice = () -> 0;

    private UsageMeter() {}

    public static UsageMeter get() {
        return INSTANCE;
    }

    /** Where the config's price overrides come from (suppliers, so a config reload takes effect at once). */
    public synchronized void prices(DoubleSupplier inputPerMTok, DoubleSupplier outputPerMTok) {
        inputPrice = inputPerMTok;
        outputPrice = outputPerMTok;
    }

    /** A world opened: start a fresh session and read the campaign ledger from its file. */
    public synchronized void open(Path usageFile) {
        file = usageFile;
        session = new Ledger();
        campaign = new Ledger();
        if (Files.exists(usageFile)) {
            try {
                Ledger l = GSON.fromJson(Files.readString(usageFile, StandardCharsets.UTF_8), Ledger.class);
                if (l != null) campaign = l;
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("Could not read {}; starting the campaign ledger fresh", usageFile, e);
            }
        }
        if (campaign.byModel == null) campaign.byModel = new LinkedHashMap<>();
    }

    public synchronized void close() {
        save();
        file = null;
    }

    public synchronized void record(String model, LlmResponse.Usage usage) {
        double usd = Pricing.cost(model, usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                usage.cacheWriteTokens(), inputPrice.getAsDouble(), outputPrice.getAsDouble());
        session.add(model, usage, usd, true);
        campaign.add(model, usage, usd, true);
        save();
    }

    public synchronized void recordVoice(int chars, double pricePerThousand) {
        double usd = chars * pricePerThousand / 1000.0;
        session.voiceLines++;
        session.voiceChars += chars;
        session.voiceCost += usd;
        campaign.voiceLines++;
        campaign.voiceChars += chars;
        campaign.voiceCost += usd;
        save();
    }

    public synchronized Ledger session() {
        return session;
    }

    public synchronized Ledger campaign() {
        return campaign;
    }

    /** The campaign is starting over; its ledger does too. The session keeps counting. */
    public synchronized void resetCampaign() {
        campaign = new Ledger();
        save();
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(campaign), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Could not save {}", file, e);
        }
    }
}
