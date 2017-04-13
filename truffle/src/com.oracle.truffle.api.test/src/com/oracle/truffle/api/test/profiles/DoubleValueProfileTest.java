/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.test.profiles;

import static com.oracle.truffle.api.test.ReflectionUtils.getStaticField;
import static com.oracle.truffle.api.test.ReflectionUtils.invoke;
import static com.oracle.truffle.api.test.ReflectionUtils.invokeStatic;
import static com.oracle.truffle.api.test.ReflectionUtils.loadRelative;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import com.oracle.truffle.api.profiles.DoubleValueProfile;
import com.oracle.truffle.api.profiles.PrimitiveValueProfile;

@RunWith(Theories.class)
public class DoubleValueProfileTest {

    @DataPoint public static final double VALUE0 = Double.MIN_VALUE;
    @DataPoint public static final double VALUE1 = -0.0f;
    @DataPoint public static final double VALUE2 = +0.0f;
    @DataPoint public static final double VALUE3 = 14.5f;
    @DataPoint public static final double VALUE4 = Double.MAX_VALUE;

    private static final double FLOAT_DELTA = 0.00001f;

    private DoubleValueProfile profile;

    @Before
    public void create() {
        profile = (DoubleValueProfile) invokeStatic(loadRelative(DoubleValueProfileTest.class, "DoubleValueProfile$Enabled"), "create");
    }

    private static boolean isGeneric(DoubleValueProfile profile) {
        return (boolean) invoke(profile, "isGeneric");
    }

    private static boolean isUninitialized(DoubleValueProfile profile) {
        return (boolean) invoke(profile, "isUninitialized");
    }

    private static double getCachedValue(DoubleValueProfile profile) {
        return (double) invoke(profile, "getCachedValue");
    }

    @Test
    public void testInitial() {
        assertThat(isGeneric(profile), is(false));
        assertThat(isUninitialized(profile), is(true));
        profile.toString(); // test that it is not crashing
    }

    @Theory
    public void testProfileOneFloat(double value) {
        double result = profile.profile(value);

        assertThat(result, is(value));
        assertEquals(getCachedValue(profile), value, FLOAT_DELTA);
        assertThat(isUninitialized(profile), is(false));
        profile.toString(); // test that it is not crashing
    }

    @SuppressWarnings("deprecation")
    @Theory
    public void testProfileTwoFloat(double value0, double value1) {
        double result0 = profile.profile(value0);
        double result1 = profile.profile(value1);

        assertEquals(result0, value0, FLOAT_DELTA);
        assertEquals(result1, value1, FLOAT_DELTA);

        if (PrimitiveValueProfile.exactCompare(value0, value1)) {
            assertEquals(getCachedValue(profile), value0, FLOAT_DELTA);
            assertThat(isGeneric(profile), is(false));
        } else {
            assertThat(isGeneric(profile), is(true));
        }
        assertThat(isUninitialized(profile), is(false));
        profile.toString(); // test that it is not crashing
    }

    @SuppressWarnings("deprecation")
    @Theory
    public void testProfileThreeFloat(double value0, double value1, double value2) {
        double result0 = profile.profile(value0);
        double result1 = profile.profile(value1);
        double result2 = profile.profile(value2);

        assertEquals(result0, value0, FLOAT_DELTA);
        assertEquals(result1, value1, FLOAT_DELTA);
        assertEquals(result2, value2, FLOAT_DELTA);

        if (PrimitiveValueProfile.exactCompare(value0, value1) && PrimitiveValueProfile.exactCompare(value1, value2)) {
            assertEquals(getCachedValue(profile), value0, FLOAT_DELTA);
            assertThat(isGeneric(profile), is(false));
        } else {
            assertThat(isGeneric(profile), is(true));
        }
        assertThat(isUninitialized(profile), is(false));
        profile.toString(); // test that it is not crashing
    }

    @Test
    public void testDisabled() {
        DoubleValueProfile p = (DoubleValueProfile) getStaticField(loadRelative(DoubleValueProfileTest.class, "DoubleValueProfile$Disabled"), "INSTANCE");
        assertThat(p.profile(VALUE0), is(VALUE0));
        assertThat(p.profile(VALUE1), is(VALUE1));
        assertThat(p.profile(VALUE2), is(VALUE2));
        assertThat(p.profile(VALUE3), is(VALUE3));
        assertThat(p.profile(VALUE4), is(VALUE4));
        p.toString(); // test that it is not crashing
    }

}
