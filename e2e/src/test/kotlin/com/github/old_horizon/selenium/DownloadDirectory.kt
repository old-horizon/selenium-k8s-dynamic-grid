package com.github.old_horizon.selenium

import com.codeborne.selenide.Driver
import io.github.rybalkinsd.kohttp.dsl.httpDelete
import io.github.rybalkinsd.kohttp.dsl.httpPost
import io.github.rybalkinsd.kohttp.ext.asStream
import io.github.rybalkinsd.kohttp.ext.httpGet
import io.github.rybalkinsd.kohttp.jackson.ext.toType
import org.openqa.selenium.io.TemporaryFilesystem
import org.openqa.selenium.io.Zip
import org.openqa.selenium.remote.SessionId
import java.io.File
import java.io.InputStream
import java.net.URL

interface DownloadDirectory {
    fun listFiles(): List<String>
    fun deleteFiles()
    fun exists(name: String): Boolean
    fun inputStream(name: String): InputStream

    companion object {
        fun of(driver: Driver, useDeprecatedEndpoints: Boolean): DownloadDirectory =
                driver.config().remote()
                        ?.let {
                            if (useDeprecatedEndpoints) {
                                return RemoteDeprecatedDownloadDirectory(URL(it), driver.sessionId)
                            } else {
                                return RemoteDownloadDirectory(URL(it), driver.sessionId)
                            }
                        }
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

}

class RemoteDownloadDirectory(private val remoteUrl: URL, private val sessionId: SessionId) : DownloadDirectory {
    private val tmpFs = TemporaryFilesystem.getDefaultTmpFS()

    override fun listFiles(): List<String> = url().httpGet().toType<FilesJson>()!!.value.names

    override fun deleteFiles() {
        URL(url()).let {
            httpDelete {
                host = it.host
                port = it.port
                path = it.path
            }
        }
    }

    override fun exists(name: String): Boolean = listFiles().contains(name)

    override fun inputStream(name: String): InputStream {
        val contents = URL(url()).let {
            httpPost {
                host = it.host
                port = it.port
                path = it.path
                body {
                    json {
                        "name" to name
                    }
                }
            }.toType<FileJson>()!!.value.contents
        }
        try {
            val dir = tmpFs.createTempDir("files", sessionId.toString())
            Zip.unzip(contents, dir)
            return dir.resolve(name).inputStream()
        } finally {
            tmpFs.deleteTemporaryFiles()
        }
    }

    private fun url(): String =
            "${remoteUrl.protocol}://${remoteUrl.host}:${remoteUrl.port}/session/$sessionId/se/files"

    data class FilesJson(val value: FilesValueJson)

    data class FilesValueJson(val names: List<String>)

    data class FileJson(val value: FileValueJson)

    data class FileValueJson(val filename: String, val contents: String)
}

class RemoteDeprecatedDownloadDirectory(
        private val remoteUrl: URL, private val sessionId: SessionId) : DownloadDirectory {
    override fun listFiles(): List<String> = buildUrl("/").httpGet().toType<FilesJson>()!!.files.map { it.name }

    override fun deleteFiles() {
        URL(buildUrl("/")).let {
            httpDelete {
                host = it.host
                port = it.port
                path = it.path
            }
        }
    }

    override fun exists(name: String): Boolean = listFiles().contains(name)

    override fun inputStream(name: String): InputStream = buildUrl("/$name").httpGet().asStream()!!

    private fun buildUrl(path: String): String =
            "${remoteUrl.protocol}://${remoteUrl.host}:${remoteUrl.port}/downloads/$sessionId$path"

    data class FilesJson(val files: List<FileJson>)

    data class FileJson(val name: String)
}
