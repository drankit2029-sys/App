package com.example.hello;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TimeFmtTest {

    @Test
    public void zero() {
        assertEquals("00:00.00", TimeFmt.formatTimer(0));
    }

    @Test
    public void centiseconds() {
        assertEquals("00:01.50", TimeFmt.formatTimer(1500));
        assertEquals("00:01.05", TimeFmt.formatTimer(1050));
        assertEquals("00:01.99", TimeFmt.formatTimer(1999));
    }

    @Test
    public void minutesCarry() {
        assertEquals("01:00.00", TimeFmt.formatTimer(60_000));
        assertEquals("12:34.56", TimeFmt.formatTimer(754_560));
    }

    @Test
    public void negativeClamps() {
        assertEquals("00:00.00", TimeFmt.formatTimer(-5));
    }
}
