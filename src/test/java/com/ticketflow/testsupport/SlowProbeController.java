package com.ticketflow.testsupport;

import java.time.Duration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Answers after {@link #DELAY}: just past the 5 s default of WebTestClient, far below {@link TestTimeouts#RESPONSE}. */
@RestController
class SlowProbeController {

    static final Duration DELAY = Duration.ofMillis(5500);

    @GetMapping("/slow")
    Mono<String> slow() {
        return Mono.just("late").delayElement(DELAY);
    }
}
