package com.github.old_horizon.selenium

import com.codeborne.selenide.Browser
import com.codeborne.selenide.Config
import com.codeborne.selenide.webdriver.ChromeDriverFactory
import org.openqa.selenium.Proxy
import org.openqa.selenium.chrome.ChromeOptions
import java.io.File

class CustomChromeDriverFactory : ChromeDriverFactory() {
    override fun createCapabilities(config: Config, browser: Browser, proxy: Proxy?, browserDownloadsFolder: File?): ChromeOptions =
            super.createCapabilities(config, browser, proxy, browserDownloadsFolder)
                    .apply {
                        setCapability("se:recordVideo", true)
                        setCapability("se:timeZone", "Asia/Tokyo")
                        setCapability("se:screenResolution", "1920x1080")
                        setCapability("se:downloadsEnabled", true)
                    }
}