package com.github.old_horizon.selenium

import com.codeborne.selenide.Condition.exactText
import com.codeborne.selenide.Configuration
import com.codeborne.selenide.Selectors.byText
import com.codeborne.selenide.Selenide
import com.codeborne.selenide.Selenide.`$`
import com.codeborne.selenide.Selenide.`$$`
import com.codeborne.selenide.WebDriverRunner
import com.google.common.html.HtmlEscapers
import com.thoughtworks.gauge.*
import com.thoughtworks.gauge.datastore.ScenarioDataStore
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.github.rybalkinsd.kohttp.dsl.httpHead
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.openqa.selenium.devtools.HasDevTools
import org.openqa.selenium.devtools.NetworkInterceptor
import org.openqa.selenium.logging.LogType
import org.openqa.selenium.remote.Augmenter
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.remote.http.Contents.utf8String
import org.openqa.selenium.remote.http.HttpHandler
import org.openqa.selenium.remote.http.HttpResponse
import org.openqa.selenium.remote.http.Route
import java.net.HttpURLConnection.HTTP_OK
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class Steps {
    private val props = loadProperties()
    private val website = Website(props.getProperty("website.port").toInt())

    private lateinit var downloadDir: DownloadDirectory

    @BeforeSuite
    fun beforeSuite() {
        loadProperties()
        configureSelenide()
        website.setup()
    }

    @AfterSuite
    fun afterSuite() {
        website.tearDown()
    }

    @BeforeScenario
    fun displayScenarioName(context: ExecutionContext) {
        """
            <html>
            <head>
            <meta charset="utf-8"/>
            </head>
            <h2>${context.currentSpecification.name}<h2>
            <h1>${context.currentScenario.name}</h1>
            </html>
        """.trimIndent().replace("\n", "")
                .let { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
                .let { "data:text/html,$it" }
                .let { Selenide.open(it) }
        TimeUnit.MILLISECONDS.sleep(500)
        Selenide.back()
        ScenarioDataStore.put("sessionId", Selenide.sessionId())
    }

    @AfterScenario
    fun scenarioFailed(context: ExecutionContext) {
        if (context.currentScenario.isFailing && WebDriverRunner.hasWebDriverStarted()) {
            Gauge.writeMessage("WebDriver session id: ${WebDriverRunner.driver().sessionId}")
            Gauge.writeMessage("Page url: ${WebDriverRunner.url()}")
            Gauge.writeMessage("Page source: \n${HtmlEscapers.htmlEscaper().escape(WebDriverRunner.source())}")
            Gauge.writeMessage("Browser logs: \n${Selenide.getWebDriverLogs(LogType.BROWSER).joinToString("\n")}")
        }
    }

    @BeforeScenario
    @AfterScenario
    fun cleanDownloadDirectory(context: ExecutionContext) {
        Selenide.open()
        downloadDir = DownloadDirectory.of(WebDriverRunner.driver(), context.allTags.contains(
                "use-deprecated-endpoints"))
        downloadDir.deleteFiles()
    }

    @Step("Navigate to <path>")
    fun navigate(path: String) {
        Selenide.open(path)
    }

    @BeforeSpec(tags = ["refreshBrowser"])
    @Step("Close session")
    fun closeSession() {
        Selenide.closeWebDriver()
        TimeUnit.SECONDS.sleep(3)
    }

    @Step("Recorded video is downloadable from Grid Hub")
    fun isVideoDownloadable() {
        WebDriverRunner.driver().config().remote()?.let(::URL)?.let {
            httpHead {
                host = it.host
                port = it.port
                path = "/videos/${ScenarioDataStore.get("sessionId")}.mp4"
            }.isSuccessful shouldBe true
        }
    }

    @Step("Click <text> link")
    fun clickLink(text: String) {
        `$$`("a").find(exactText(text)).click()
    }

    @Step("File <name> has downloaded")
    fun hasDownloaded(name: String) {
        TimeUnit.SECONDS.sleep(1)
        downloadDir.exists(name) shouldBe true
    }

    @Step("Content of downloaded <actual> equals to <expected>")
    fun downloadedContentEquals(actual: String, expected: String) {
        downloadDir.inputStream(actual).readAllBytes() shouldBeEqualTo read(expected).readAllBytes()
    }

    @Step("When accessing <url> then <content> will be returned")
    fun interceptRequest(url: String, content: String) {
        val hasDevTools = when (val webDriver = WebDriverRunner.getAndCheckWebDriver()) {
            is HasDevTools -> webDriver
            is RemoteWebDriver -> Augmenter().augment(webDriver)
            else -> throw IllegalArgumentException("Unexpected webDriver: ${webDriver.javaClass.name}")
        }
        NetworkInterceptor(hasDevTools, Route.get(url).to(Supplier {
            HttpHandler { HttpResponse().setStatus(HTTP_OK).setContent(utf8String("<html>$content</html>")) }
        }))
    }

    @Step("Page content equals to <content>")
    fun pageContentEquals(content: String) {
        `$`("html").shouldHave(exactText(content))
    }

    @Step("Upload file <file> to <text>")
    fun uploadFile(file: String, text: String) {
        `$`(byText(text)).parent().find("input[type=\"file\"]").uploadFromClasspath(file)
    }

    @Step("Click <text> button")
    fun clickButton(text: String) {
        `$$`("button").find(exactText(text)).click()
    }

    private fun loadProperties(): Properties {
        read("uat.properties").use {
            return Properties()
                    .apply { load(it) }
                    .onEach { System.setProperty(it.key as String, it.value as String) }
        }
    }

    private fun configureSelenide() {
        Configuration.browser = CustomChromeDriverFactory::class.qualifiedName
        Configuration.remote = getGridUrl()
        Configuration.baseUrl = "http://${getHostAddress()}:${website.port}"
    }

    private fun getGridUrl(): String = KubernetesClientBuilder().build().use {
        val internalIp = it.nodes().list().items.first().status.addresses.first().address
        val nodePort = it.services().inNamespace(System.getProperty("selenium.namespace"))
                .withName("selenium").get().spec.ports.first().nodePort
        return "http://$internalIp:$nodePort/wd/hub"
    }

    private fun getHostAddress(): String = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .first { !it.isLoopbackAddress && it.address.size == 4 }
            .hostAddress

    private fun read(name: String) = javaClass.classLoader.getResourceAsStream(name)!!
}