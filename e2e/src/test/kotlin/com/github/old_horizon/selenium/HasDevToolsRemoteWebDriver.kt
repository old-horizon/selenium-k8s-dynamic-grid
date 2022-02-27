package com.github.old_horizon.selenium

import org.openqa.selenium.Capabilities
import org.openqa.selenium.MutableCapabilities
import org.openqa.selenium.WebDriver
import org.openqa.selenium.devtools.CdpVersionFinder
import org.openqa.selenium.devtools.DevTools
import org.openqa.selenium.devtools.HasDevTools
import org.openqa.selenium.devtools.SeleniumCdpConnection
import org.openqa.selenium.devtools.noop.NoOpCdpInfo
import org.openqa.selenium.remote.HttpCommandExecutor
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.remote.TracedCommandExecutor
import java.net.URI
import java.net.URL
import java.util.*

private const val CDP_VERSION = "se:cdpVersion"
private const val CDP = "se:cdp"

class HasDevToolsRemoteWebDriver(private val webDriver: RemoteWebDriver) : HasDevTools, WebDriver by webDriver {
    override fun maybeGetDevTools(): Optional<DevTools> {
        val info = CdpVersionFinder().match(webDriver.capabilities.cdpVersion).orElseGet(::NoOpCdpInfo)
        return URI(webDriver.capabilities.getCapability(CDP) as String)
                .rewriteHostWith(getRemoteServer(webDriver).toURI())
                .let { MutableCapabilities().apply { setCapability(CDP, it.toString()) } }
                .let(SeleniumCdpConnection::create)
                .map { conn -> DevTools(info::getDomains, conn) }
    }
}

private val Capabilities.cdpVersion: String
    get() = (getCapability(CDP_VERSION) as? String) ?: browserVersion

private fun URI.rewriteHostWith(other: URI): URI = URI(scheme, userInfo, other.host, other.port, path, query, fragment)

private fun getRemoteServer(webDriver: RemoteWebDriver): URL = when (val executor = webDriver.commandExecutor) {
    is HttpCommandExecutor -> executor.addressOfRemoteServer
    is TracedCommandExecutor -> executor.addressOfRemoteServer
    else -> throw AssertionError("Unexpected commandExecutor: ${executor::class.qualifiedName}")
}

private val TracedCommandExecutor.addressOfRemoteServer: URL
    get() = (getPrivateProperty("delegate") as HttpCommandExecutor).addressOfRemoteServer

@Suppress("UNCHECKED_CAST")
private fun <T : Any> T.getPrivateProperty(property: String): Any {
    return javaClass.getDeclaredField(property).apply { isAccessible = true }.get(this)
}
