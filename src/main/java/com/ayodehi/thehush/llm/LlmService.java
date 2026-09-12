package com.ayodehi.thehush.llm;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.TheHushMod;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the configured provider and the worker threads that talk to it. */
public final class LlmService {
    private static final LlmService INSTANCE = new LlmService();

    private @Nullable LlmProvider provider;
    private @Nullable ExecutorService executor;
    private String status = "not configured";

    private LlmService() {}

    public static LlmService get() {
        return INSTANCE;
    }

    /** Called on server start, after config values are loaded. Safe to call again to pick up changes. */
    public synchronized void configure() {
        shutdown();
        String apiKey = Config.API_KEY.get().strip();
        if (apiKey.isEmpty()) {
            String env = Config.API_KEY_ENV_VAR.get().strip();
            String fromEnv = env.isEmpty() ? null : System.getenv(env);
            apiKey = fromEnv == null ? "" : fromEnv.strip();
        }
        if (apiKey.isEmpty()) {
            provider = null;
            status = "no API key: set llm.apiKey in config/hush-common.toml or export "
                    + Config.API_KEY_ENV_VAR.get();
            TheHushMod.LOGGER.warn("Villager AI: {}", status);
            return;
        }
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("thehush-llm-", 0).factory());
        ClaudeProvider.Settings settings = new ClaudeProvider.Settings(
                apiKey,
                Config.BASE_URL.get().replaceAll("/+$", ""),
                Config.MODEL.get(),
                Config.MAX_TOKENS.get(),
                Config.EFFORT.get(),
                Duration.ofSeconds(Config.REQUEST_TIMEOUT_SECONDS.get()));
        provider = new ClaudeProvider(settings, executor);
        status = "ok";
    }

    public synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        provider = null;
    }

    public synchronized @Nullable LlmProvider provider() {
        return provider;
    }

    public synchronized String describe() {
        return provider == null ? status : provider.describe();
    }
}
