package com.baah.nativehost
import org.junit.Assert.*
import org.junit.Test
class ReleaseFallbackTest {
    @Test fun publicFeedUsesFirstReleaseAndIgnoresOtherLinks() {
        val feed = """<feed><link href="https://github.com/BlueArchiveArisHelper"/>
            <entry><link rel="alternate" href="https://github.com/BlueArchiveArisHelper/BAAH/releases/tag/BAAH2.6.0-beta"/></entry>
            <entry><link href="https://github.com/BlueArchiveArisHelper/BAAH/releases/tag/BAAH2.5.7"/></entry></feed>"""
        assertEquals("BAAH2.6.0-beta", ReleasePolicy.newestPublicRelease(feed))
        assertEquals("BAAH2.5.7", ReleasePolicy.tagFromReleaseUrl("https://github.com/BlueArchiveArisHelper/BAAH/releases/tag/BAAH2.5.7"))
    }
    @Test(expected = IllegalArgumentException::class) fun releaseUrlCannotSupplyAnotherRepositoryOrBranch() {
        ReleasePolicy.tagFromReleaseUrl("https://github.com/BlueArchiveArisHelper/BAAH/tree/main")
    }
    @Test(expected = IllegalStateException::class) fun feedWithoutPublishedReleaseDoesNotUseBranch() {
        ReleasePolicy.newestPublicRelease("""<feed><link href="https://github.com/BlueArchiveArisHelper/BAAH/tree/main"/></feed>""")
    }
}
