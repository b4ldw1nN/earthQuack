package com.example.earthquack.sftp.fs

import com.example.earthquack.sftp.LocalAndroidFileSystem
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Test

/**
 * Scratch: find which NIO operation the SFTP subsystem needs that this provider
 * does not implement. Deleted once the gap is closed.
 */
class ProviderScratchTest {

    @Test
    fun probe() {
        val root = Files.createTempDirectory("scratch").toFile()
        val fs = AndroidFileSystemNio(LocalAndroidFileSystem(root))

        val ops = listOf(
            "newDirectoryStream" to { Files.newDirectoryStream(fs.getPath("/")) },
            "readAttributes basic" to {
                Files.readAttributes(fs.getPath("/"), java.nio.file.attribute.BasicFileAttributes::class.java)
            },
            "readAttributes posix" to {
                Files.readAttributes(fs.getPath("/"), java.nio.file.attribute.PosixFileAttributes::class.java)
            },
            "readAttributes string" to {
                Files.readAttributes(fs.getPath("/"), "basic:size,isDirectory")
            },
            "readAttributes string posix" to {
                Files.readAttributes(fs.getPath("/"), "posix:permissions,owner,group")
            },
            "readAttributes unix" to {
                Files.readAttributes(fs.getPath("/"), "unix:mode")
            },
            "newByteChannel write" to {
                Files.newByteChannel(
                    fs.getPath("/x.bin"),
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                )
            },
            "newByteChannel read" to {
                Files.newByteChannel(fs.getPath("/x.bin"), setOf(StandardOpenOption.READ))
            },
            "getFileStore" to { Files.getFileStore(fs.getPath("/")) },
            "getFileAttributeView" to {
                Files.getFileAttributeView(fs.getPath("/"), java.nio.file.attribute.BasicFileAttributeView::class.java)
            },
            "isSameFile" to { Files.isSameFile(fs.getPath("/"), fs.getPath("/")) },
            "isHidden" to { Files.isHidden(fs.getPath("/")) },
            "checkAccess" to { fs.provider().checkAccess(fs.getPath("/"), java.nio.file.AccessMode.READ) },
            "copy" to {
                Files.copy(fs.getPath("/x.bin"), fs.getPath("/y.bin"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            },
            "move" to {
                Files.move(fs.getPath("/y.bin"), fs.getPath("/z.bin"))
            },
            "setAttribute" to {
                Files.setAttribute(fs.getPath("/"), "basic:lastModifiedTime", java.nio.file.attribute.FileTime.fromMillis(0))
            },
            "exists" to { Files.exists(fs.getPath("/")) },
            "newDirectoryStream with filter" to {
                Files.newDirectoryStream(fs.getPath("/")) { true }
            }
        )

        ops.forEach { (name, block) ->
            val result = runCatching { block() }
            println(
                (if (result.isSuccess) "OK   " else "FAIL ") + name + " -> " +
                    result.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message }
            )
        }
    }
}
