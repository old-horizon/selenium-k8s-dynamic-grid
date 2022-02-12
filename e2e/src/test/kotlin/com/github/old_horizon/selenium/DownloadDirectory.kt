package com.github.old_horizon.selenium

import com.codeborne.selenide.Driver
import io.github.rybalkinsd.kohttp.dsl.httpDelete
import io.github.rybalkinsd.kohttp.ext.asStream
import io.github.rybalkinsd.kohttp.ext.httpGet
import io.github.rybalkinsd.kohttp.jackson.ext.toType
import okhttp3.Response
import org.openqa.selenium.remote.SessionId
import java.io.File
import java.io.InputStream
import java.net.URL

interface DownloadDirectory {
    fun listFiles(): List<String>
    fun deleteFiles()
    fun exists(name: String): Boolean
    fun inputStream(name: String): InputStream
    fun deleteFile(name: String)

    companion object {
        fun of(driver: Driver): DownloadDirectory =
                driver.config().remote()
                        ?.let { RemoteDownloadDirectory(URL(it), driver.sessionId) }
                        ?: LocalDownloadDirectory(driver.browserDownloadsFolder()!!.toFile())
    }
}

class LocalDownloadDirectory(private val dir: File) : DownloadDirectory {
    override fun listFiles(): List<String> = dir.list()!!.toList()

    override fun deleteFiles() {
        dir.deleteRecursively()
        dir.mkdir()
    }

    override fun exists(name: String): Boolean = dir.resolve(name).exists()

    override fun inputStream(name: String): InputStream = dir.resolve(name).inputStream()

    override fun deleteFile(name: String) {
        dir.resolve(name).delete()
    }
}

class RemoteDownloadDirectory(private val remoteUrl: URL, private val sessionId: SessionId) : DownloadDirectory {
    override fun listFiles(): List<String> = buildUrl("/").httpGet().toType<FilesJson>()!!.files.map { it.name }

    override fun deleteFiles() {
        buildUrl("/").httpDelete()
    }

    override fun exists(name: String): Boolean = listFiles().contains(name)

    override fun inputStream(name: String): InputStream = buildUrl("/$name").httpGet().asStream()!!

    override fun deleteFile(name: String) {
        buildUrl("/$name").httpDelete()
    }

    private fun buildUrl(path: String): String =
            "${remoteUrl.protocol}://${remoteUrl.host}:${remoteUrl.port}/downloads/$sessionId$path"

    private fun String.httpDelete(): Response = URL(this).let {
        httpDelete {
            host = it.host
            port = it.port
            path = it.path
        }
    }

    data class FilesJson(val files: List<FileJson>)

    data class FileJson(val name: String)
}
