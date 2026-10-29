package com.storeprice.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTextTest {

    @Test
    fun `display normalization trims and collapses whitespace`() {
        assertEquals(
            "Молоко 3,2%",
            SearchText.normalizeDisplay("  Молоко \t 3,2%  "),
        )
    }

    @Test
    fun `search normalization is case insensitive for Russian`() {
        assertEquals("молоко 3,2%", SearchText.normalize("  МОЛОКО   3,2% "))
    }

    @Test
    fun `non breaking space is treated as whitespace`() {
        assertEquals("12 345", SearchText.normalizeDisplay("12\u00A0345"))
    }

    @Test
    fun `like metacharacters are escaped`() {
        assertEquals("100\\%\\_\\\\", SearchText.escapeLike("100%_\\"))
    }
}
