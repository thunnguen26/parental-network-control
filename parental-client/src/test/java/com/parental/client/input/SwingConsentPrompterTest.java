package com.parental.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class SwingConsentPrompterTest {

    @Test
    void staleDialogCannotCompleteReplacementRequest() {
        List<ConsentPrompter.Decision> decisions = new ArrayList<>();
        AtomicReference<SwingConsentPrompter.PendingRequest> current = new AtomicReference<>();
        SwingConsentPrompter.PendingRequest oldRequest =
                new SwingConsentPrompter.PendingRequest(decisions::add);
        SwingConsentPrompter.PendingRequest newRequest =
                new SwingConsentPrompter.PendingRequest(decisions::add);
        current.set(oldRequest);
        current.set(newRequest);

        assertFalse(SwingConsentPrompter.completeIfCurrent(
                current, oldRequest, ConsentPrompter.Decision.ACCEPTED));
        assertTrue(decisions.isEmpty(), "A stale dialog must not grant the new request");

        assertTrue(SwingConsentPrompter.completeIfCurrent(
                current, newRequest, ConsentPrompter.Decision.DECLINED));
        assertEquals(List.of(ConsentPrompter.Decision.DECLINED), decisions);
    }

    @Test
    void consentRequestCanOnlyCompleteOnce() {
        List<ConsentPrompter.Decision> decisions = new ArrayList<>();
        AtomicReference<SwingConsentPrompter.PendingRequest> current = new AtomicReference<>();
        SwingConsentPrompter.PendingRequest request =
                new SwingConsentPrompter.PendingRequest(decisions::add);
        current.set(request);

        assertTrue(SwingConsentPrompter.completeIfCurrent(
                current, request, ConsentPrompter.Decision.ACCEPTED));
        assertFalse(SwingConsentPrompter.completeIfCurrent(
                current, request, ConsentPrompter.Decision.DECLINED));
        assertEquals(List.of(ConsentPrompter.Decision.ACCEPTED), decisions);
    }
}
