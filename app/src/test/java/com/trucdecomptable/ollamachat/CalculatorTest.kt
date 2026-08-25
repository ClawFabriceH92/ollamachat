package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.tools.Calculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalculatorTest {

    @Test
    fun `basic arithmetic`() {
        assertEquals(4.0, Calculator.evaluate("2+2"), 0.001)
        assertEquals(1.0, Calculator.evaluate("3-2"), 0.001)
        assertEquals(6.0, Calculator.evaluate("2*3"), 0.001)
        assertEquals(2.5, Calculator.evaluate("10/4"), 0.001)
    }

    @Test
    fun `precedence and parentheses`() {
        assertEquals(7.0, Calculator.evaluate("1+2*3"), 0.001)
        assertEquals(9.0, Calculator.evaluate("(1+2)*3"), 0.001)
        assertEquals(1024.0, Calculator.evaluate("2^10"), 0.001)
        assertEquals(-5.0, Calculator.evaluate("-5"), 0.001)
    }

    @Test
    fun `division by zero rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("10/0") }
    }

    @Test
    fun `invalid expressions rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("abc") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("2+") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("") }
    }
}
