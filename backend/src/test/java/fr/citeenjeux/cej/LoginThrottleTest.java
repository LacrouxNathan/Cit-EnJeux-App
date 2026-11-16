package fr.citeenjeux.cej;

import fr.citeenjeux.cej.infrastructure.LoginThrottle;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {
    @Test void limitIsPerAddressAndExpires() {
        class MutableClock extends Clock {
            Instant now = Instant.parse("2026-11-16T12:00:00Z");
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new MutableClock();
        var throttle = new LoginThrottle(2, Duration.ofMinutes(15), clock);
        assertThat(throttle.allow("a")).isTrue();
        assertThat(throttle.allow("a")).isTrue();
        assertThat(throttle.allow("a")).isFalse();
        assertThat(throttle.allow("b")).isTrue();
        clock.now = clock.now.plusSeconds(900);
        assertThat(throttle.allow("a")).isTrue();
    }
}
