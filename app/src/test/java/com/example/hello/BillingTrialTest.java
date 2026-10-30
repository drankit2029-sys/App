package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BillingTrialTest {

    @Test
    public void billingPeriodsFormatAsHumanTrialLengths() {
        assertEquals("7 days", TrialAndBillingManager.formatBillingPeriod("P7D"));
        assertEquals("1 day", TrialAndBillingManager.formatBillingPeriod("P1D"));
        assertEquals("14 days", TrialAndBillingManager.formatBillingPeriod("P2W"));
        assertEquals("1 month", TrialAndBillingManager.formatBillingPeriod("P1M"));
        assertEquals("3 months", TrialAndBillingManager.formatBillingPeriod("P3M"));
        assertEquals("1 year", TrialAndBillingManager.formatBillingPeriod("P1Y"));
    }

    @Test
    public void malformedBillingPeriodsAreRejected() {
        assertNull(TrialAndBillingManager.formatBillingPeriod(null));
        assertNull(TrialAndBillingManager.formatBillingPeriod(""));
        assertNull(TrialAndBillingManager.formatBillingPeriod("P"));
        assertNull(TrialAndBillingManager.formatBillingPeriod("PXD"));
        assertNull(TrialAndBillingManager.formatBillingPeriod("P0D"));
        assertNull(TrialAndBillingManager.formatBillingPeriod("P7H"));
    }
}
