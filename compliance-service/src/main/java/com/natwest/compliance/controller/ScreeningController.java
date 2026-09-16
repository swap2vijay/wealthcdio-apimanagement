package com.natwest.compliance.controller;

import com.natwest.compliance.dto.ScreeningRequest;
import com.natwest.compliance.dto.ScreeningResponse;
import com.natwest.compliance.model.ScreeningOutcome;
import com.natwest.compliance.service.ScreeningPolicy;

import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/** The single endpoint this service exposes. */
@RestController
@RequestMapping("/api/v1/screenings")
public class ScreeningController {

    private static final Logger log = LogManager.getLogger(ScreeningController.class);

    private final ScreeningPolicy policy;
    private final Clock clock;

    public ScreeningController(ScreeningPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Screens a proposed transfer.
     *
     * <p>Responds {@code 200} whether the answer is yes or no - see {@link ScreeningResponse} for why
     * a rejection is not a 4xx.
     *
     * <p>Every decision is logged with its reference, because a compliance decision that cannot be
     * explained afterwards is not much of a control.
     */
    @PostMapping
    public ScreeningResponse screen(@Valid @RequestBody ScreeningRequest request) {
        ScreeningOutcome outcome = policy.screen(
                request.sourceAccountId(), request.destinationAccountId(), request.amount());

        if (outcome.isApproved()) {
            log.info("Screened transfer [ref={}] {} -> {} for {}: APPROVED",
                    request.reference(), request.sourceAccountId(),
                    request.destinationAccountId(), request.amount());
        } else {
            log.warn("Screened transfer [ref={}] {} -> {} for {}: REJECTED ({})",
                    request.reference(), request.sourceAccountId(),
                    request.destinationAccountId(), request.amount(), outcome.reason());
        }

        return ScreeningResponse.of(request.reference(), outcome, clock.instant());
    }
}

