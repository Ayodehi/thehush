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
        Config.LlmBackend kind = Config.LLM_PROVIDER.get();
        if (kind == Config.LlmBackend.OLLAMA) {
            executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("thehush-llm-", 0).factory());
            provider = new OllamaProvider(new OllamaProvider.Settings(
                    Config.OLLAMA_URL.get().strip().replaceAll("/+$", ""),
                    Config.OLLAMA_MODEL.get().strip(),
                    Config.MAX_TOKENS.get(),
                    Config.OLLAMA_CONTEXT.get(),
                    Config.OLLAMA_THINK.get(),
                    Duration.ofSeconds(Config.REQUEST_TIMEOUT_SECONDS.get())), executor);
            status = "ok";
            return;
        }
        if (kind == Config.LlmBackend.OPENAI) {
            String key = Config.OPENAI_API_KEY.get().strip();
            if (key.isEmpty()) {
                String env = Config.OPENAI_API_KEY_ENV_VAR.get().strip();
                String fromEnv = env.isEmpty() ? null : System.getenv(env);
                key = fromEnv == null ? "" : fromEnv.strip();
            }
            executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("thehush-llm-", 0).factory());
            provider = new OpenAiProvider(new OpenAiProvider.Settings(
                    Config.OPENAI_URL.get().strip(),
                    key,
                    Config.OPENAI_MODEL.get().strip(),
                    Config.MAX_TOKENS.get(),
                    Config.OPENAI_REASONING_EFFORT.get().strip(),
                    Config.OPENAI_INPUT_PRICE.get(),
                    Config.OPENAI_OUTPUT_PRICE.get(),
                    Duration.ofSeconds(Config.REQUEST_TIMEOUT_SECONDS.get())), executor);
            status = "ok";
            return;
        }
        String apiKey = Config.API_KEY.get().strip();
        if (apiKey.isEmpty()) {
            String env = Config.API_KEY_ENV_VAR.get().strip();
            String fromEnv = env.isEmpty() ? null : System.getenv(env);
            apiKey = fromEnv == null ? "" : fromEnv.strip();
        }
        if (apiKey.isEmpty()) {
            provider = null;
            status = "no API key: set llm.anthropic.apiKey in config/thehush-common.toml, export "
                    + Config.API_KEY_ENV_VAR.get() + ", or set llm.provider = \"ollama\" for a local model";
            TheHushMod.LOGGER.warn("The Hush: {}", status);
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
