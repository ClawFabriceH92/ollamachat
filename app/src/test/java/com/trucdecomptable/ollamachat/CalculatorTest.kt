package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.tools.Calculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `small results are not rounded away`() {
        // A monthly rate used to come back as 0 after rounding to 3 decimals.
        assertEquals(1.0 / 3000, Calculator.evaluate("1/3000"), 1e-15)
        assertEquals(Math.pow(1.05, 1.0 / 12), Calculator.evaluate("1.05^(1/12)"), 1e-15)
    }

    @Test
    fun `large results do not saturate`() {
        assertEquals(2e16, Calculator.evaluate("2*10^16"), 0.0)
    }

    @Test
    fun `results are written out in full, to fifteen significant digits`() {
        assertEquals("12345678.9", Calculator.format(12345678.9))
        assertEquals("0.000333333333333333", Calculator.format(1.0 / 3000))
        // Exact in a double, so exact in the answer: twelve digits made it 1234567890120.
        assertEquals("1234567890124", Calculator.format(Calculator.evaluate("1234567890123+1")))
        assertEquals("0.3", Calculator.format(0.1 + 0.2))
        assertEquals("0", Calculator.format(-0.0))
        assertEquals("-2.5", Calculator.format(-2.5))
        assertTrue(Calculator.format(2e16).startsWith("20000000000000000 "))
    }

    @Test
    fun `a french decimal comma is read when it cannot be a thousands separator`() {
        assertEquals(3.0, Calculator.evaluate("1,5*2"), 1e-12)
        assertEquals(12.5, Calculator.evaluate("12,50"), 1e-12)
        assertEquals(1234.56, Calculator.evaluate("1\u00A0234,56"), 1e-9)
    }

    @Test
    fun `an ambiguous comma is refused rather than guessed`() {
        // 1.234 or 1234? A wrong guess answers a thousand times off, silently.
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("1,234*3") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("1,5+2.5") }
    }

    @Test
    fun `a percentage is a hundredth`() {
        assertEquals(0.15, Calculator.evaluate("15%"), 1e-12)
        assertEquals(30.0, Calculator.evaluate("200*15%"), 1e-12)
        assertEquals(-0.05, Calculator.evaluate("-5%"), 1e-12)
        assertEquals(1200.0, Calculator.evaluate("1000+1000*20%"), 1e-9)
        // The usual way to add VAT: both readings of "1+20%" give 1.2.
        assertEquals(1200.0, Calculator.evaluate("1000*(1+20%)"), 1e-9)
        assertEquals(950.0, Calculator.evaluate("1000*(1-5%)"), 1e-9)
    }

    @Test
    fun `a percentage added to a number is refused rather than guessed`() {
        // 1200 to a person, 1000.2 to the parser.
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("1000+20%") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("1000-(5)%") }
        // Parentheses around it change nothing.
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("1000+(20%)") }
    }

    @Test
    fun `invalid expressions rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("abc") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("2+") }
        assertThrows(IllegalArgumentException::class.java) { Calculator.evaluate("") }
    }
}
