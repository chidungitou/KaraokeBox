package com.karaokebox.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun parseTag() {
        assertEquals(UpdateVersion.Tag("1.6.0", "v0.26.5"), UpdateVersion.parseTag("v1.6.0-np-v0.26.5"))
        assertNull(UpdateVersion.parseTag("v1.6.0"))
    }

    @Test fun newer() {
        assertTrue(UpdateVersion.isNewer("v1.7.0-np-v0.26.5", "1.6.0", "v0.26.5"))
        assertTrue(UpdateVersion.isNewer("v1.6.0-np-v0.27.1", "1.6.0", "v0.26.5"))
        assertTrue(UpdateVersion.isNewer("v1.10.0-np-v0.26.5", "1.9.0", "v0.26.5"))
        assertFalse(UpdateVersion.isNewer("v1.6.0-np-v0.26.5", "1.6.0", "v0.26.5"))
        assertFalse(UpdateVersion.isNewer("v1.5.9-np-v0.30.0", "1.6.0", "v0.26.5"))
        assertFalse(UpdateVersion.isNewer("abc", "1.6.0", "v0.26.5"))
    }
}
