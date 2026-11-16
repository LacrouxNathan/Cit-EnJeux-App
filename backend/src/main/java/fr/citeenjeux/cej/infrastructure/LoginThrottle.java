package fr.citeenjeux.cej.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Limite par adresse réseau directe, sans faire confiance à X-Forwarded-For. */
@Component
public class LoginThrottle {
    private record Bucket(long expiresAt, int count) { }
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final int limit;
    private final long window;
    private final Clock clock;

    @Autowired
    public LoginThrottle(@Value("${cej.auth.max-attempts:10}") int limit,
                         @Value("${cej.auth.window-seconds:900}") long seconds) {
        this(limit, Duration.ofSeconds(seconds), Clock.systemUTC());
    }

    public LoginThrottle(int limit, Duration window, Clock clock) {
        if (limit < 1 || window.isNegative() || window.isZero()) throw new IllegalArgumentException("Limite invalide");
        this.limit = limit; this.window = window.toMillis(); this.clock = clock;
    }

    public synchronized boolean allow(String address) {
        long now = clock.millis();
        buckets.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Bucket previous = buckets.get(address);
        if (previous == null) {
            if (buckets.size() >= 10000) return false;
            buckets.put(address, new Bucket(now + window, 1));
            return true;
        }
        if (previous.count() >= limit) return false;
        buckets.put(address, new Bucket(previous.expiresAt(), previous.count() + 1));
        return true;
    }

    public long retryAfterSeconds() { return (window + 999) / 1000; }
}
