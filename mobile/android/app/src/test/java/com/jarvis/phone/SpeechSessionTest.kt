package com.jarvis.phone

import org.junit.Assert.*
import org.junit.Test

class SpeechSessionTest {
    @Test fun foregroundIsRequiredAndResumeAllowsListening() {
        val session = SpeechSession()
        assertNull(session.begin())
        session.resume()
        assertNotNull(session.begin())
    }
    @Test fun repeatedTapCannotStartASecondRecognizer() {
        val session = SpeechSession()
        session.resume()
        val token = session.begin()!!
        assertNull(session.begin())
        assertTrue(session.accepts(token))
    }
    @Test fun cancellationInvalidatesOldCallbacks() {
        val session = SpeechSession()
        session.resume()
        val old = session.begin()!!
        session.cancel()
        val current = session.begin()!!
        assertFalse(session.finish(old))
        assertTrue(session.accepts(current))
    }
    @Test fun pauseStopsListeningAndCannotRestartUntilResume() {
        val session = SpeechSession()
        session.resume()
        val token = session.begin()!!
        session.pause()
        assertFalse(session.active)
        assertFalse(session.accepts(token))
        assertNull(session.begin())
        session.resume()
        assertNotNull(session.begin())
    }
    @Test fun finalResultCanOnlyBeDeliveredOnce() {
        val session = SpeechSession()
        session.resume()
        val token = session.begin()!!
        assertTrue(session.finish(token))
        assertFalse(session.finish(token))
        assertNotNull(session.begin())
    }
}
