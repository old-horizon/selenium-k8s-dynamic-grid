package com.github.old_horizon.selenium

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

data class Website(val port: Int) {
    private val server = WireMockServer(options().port(port))

    fun setup() {
        server.start()
    }

    fun tearDown() {
        server.stop()
    }
}