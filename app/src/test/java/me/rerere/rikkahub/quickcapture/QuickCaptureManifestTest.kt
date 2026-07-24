package me.rerere.rikkahub.quickcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class QuickCaptureManifestTest {
    @Test
    fun `quick capture services are private and declare their foreground types`() {
        val manifest = parseManifest()
        val services = manifest.getElementsByTagName("service").asElements()
        val overlay = services.singleOrNull { it.androidName == ".quickcapture.QuickCaptureOverlayService" }
        val projection = services.singleOrNull { it.androidName == ".quickcapture.QuickCaptureMediaProjectionService" }

        assertNotNull(overlay)
        assertNotNull(projection)
        assertEquals("false", overlay!!.androidExported)
        assertEquals("specialUse", overlay.androidForegroundServiceType)
        assertTrue(
            overlay.getElementsByTagName("property").asElements().any {
                it.androidName == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            },
        )
        assertEquals("false", projection!!.androidExported)
        assertEquals("mediaProjection", projection.androidForegroundServiceType)
    }

    @Test
    fun `quick capture manifest includes both required foreground permissions`() {
        val permissions = parseManifest().getElementsByTagName("uses-permission").asElements().map { it.androidName }
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))
    }

    private fun parseManifest() = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private val Element.androidName: String get() = getAttribute("android:name")
    private val Element.androidExported: String get() = getAttribute("android:exported")
    private val Element.androidForegroundServiceType: String get() = getAttribute("android:foregroundServiceType")
    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }
}
