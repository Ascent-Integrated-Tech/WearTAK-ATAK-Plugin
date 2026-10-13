/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhoneFixValidatorTest {
    @Test
    public void acceptsRealPosition() {
        assertTrue(PhoneFixValidator.isUsable(32.709161, -117.159840));
    }

    @Test
    public void acceptsEquatorOrMeridianButNotBoth() {
        assertTrue(PhoneFixValidator.isUsable(0.0, 10.0));
        assertTrue(PhoneFixValidator.isUsable(10.0, 0.0));
    }

    @Test
    public void rejectsZeroZeroPlaceholder() {
        assertFalse(PhoneFixValidator.isUsable(0.0, 0.0));
    }

    @Test
    public void rejectsNonFiniteValues() {
        assertFalse(PhoneFixValidator.isUsable(Double.NaN, 10.0));
        assertFalse(PhoneFixValidator.isUsable(10.0, Double.NaN));
        assertFalse(PhoneFixValidator.isUsable(Double.POSITIVE_INFINITY, 10.0));
        assertFalse(PhoneFixValidator.isUsable(10.0, Double.NEGATIVE_INFINITY));
    }

    @Test
    public void rejectsOutOfRangeValues() {
        assertFalse(PhoneFixValidator.isUsable(90.1, 10.0));
        assertFalse(PhoneFixValidator.isUsable(-90.1, 10.0));
        assertFalse(PhoneFixValidator.isUsable(10.0, 180.1));
        assertFalse(PhoneFixValidator.isUsable(10.0, -180.1));
    }
}
