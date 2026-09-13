package com.bookshelf.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsbnTest {
    @Test
    fun validatesIsbn13() {
        assertTrue(Isbn.validate13("9785389143852"))
    }

    @Test
    fun convertsIsbn10To13() {
        assertEquals("9780140328721", Isbn.normalize("0-14-032872-6"))
    }

    @Test
    fun rejectsNonBookEan() {
        assertNull(Isbn.normalize("4006381333931"))
    }
}
