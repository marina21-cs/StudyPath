package com.studypath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordPolicyTest {
    @Test fun `eight or more characters are accepted`() {
        assertTrue(PasswordPolicy.accepts("abcdefgh"))
        assertTrue(PasswordPolicy.accepts("LocalOffline123!"))
    }

    @Test fun `the former six character minimum, shorter values and whitespace-only values are rejected`() {
        for (password in listOf("", "abc", "abcdef", "abcdefg", "        ")) assertFalse(password, PasswordPolicy.accepts(password))
    }

    @Test fun `the user-facing requirement states the enforced minimum`() {
        assertEquals(8, PasswordPolicy.MIN_LENGTH)
        assertTrue(PasswordPolicy.REQUIREMENT.contains(PasswordPolicy.MIN_LENGTH.toString()))
    }
}
