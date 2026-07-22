package me.rerere.rikkahub.plugin

import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginNetworkAddressPolicyTest {
    @Test
    fun `private special and documentation addresses are rejected`() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "::1",
            "fc00::1",
            "2001:db8::1",
        ).forEach { raw ->
            assertFalse(raw, PluginNetworkAddressPolicy.isPublic(InetAddress.getByName(raw)))
        }
    }

    @Test
    fun `ordinary public numeric addresses are accepted`() {
        assertTrue(PluginNetworkAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8")))
        assertTrue(PluginNetworkAddressPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun `redirects cannot escape the manifest-authorized host`() {
        assertTrue(
            PluginNetworkRedirectPolicy.isAllowed(
                "api.example.com",
                "https://api.example.com/next".toHttpUrl(),
            )
        )
        assertFalse(
            PluginNetworkRedirectPolicy.isAllowed(
                "api.example.com",
                "https://cdn.example.com/next".toHttpUrl(),
            )
        )
        assertFalse(
            PluginNetworkRedirectPolicy.isAllowed(
                "api.example.com",
                "https://api.example.com:8443/next".toHttpUrl(),
            )
        )
    }
}
