package com.example.earthquack.sftp.fs

import com.example.earthquack.sftp.AndroidFileSystem
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.session.SessionContext
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.Path

/**
 * Hands sshd a [FileSystem] backed by [AndroidFileSystem].
 *
 * sshd calls [createFileSystem] once per SFTP session and closes it when the
 * session ends, which is why a new view is built each time — but the
 * [AndroidFileSystem] behind it is the same object for the life of the server,
 * so nothing is re-opened or re-scanned per client.
 *
 * [getUserHomeDir] is what a client sees as its start directory. Returning the
 * root (rather than anything absolute) is what makes `cd /` and `cd ~` land
 * inside the served tree for every client, including rclone, which opens the
 * remote with an empty path.
 */
class AndroidFileSystemFactory(
    private val backend: AndroidFileSystem
) : FileSystemFactory {

    override fun getUserHomeDir(session: SessionContext): Path =
        view().getPath("/")

    override fun createFileSystem(session: SessionContext): FileSystem = view()

    private fun view(): AndroidFileSystemNio = AndroidFileSystemNio(backend)
}

/**
 * Guard for the case where the served root has disappeared.
 *
 * Android can revoke storage permission or unmount a card while the server is
 * running. This is checked at start-up so the failure is "the configured root is
 * not usable, here is why" instead of a bind success followed by every client
 * getting permission errors.
 */
fun requireUsableRoot(root: java.io.File) {
    if (!root.isDirectory) {
        throw IOException("SFTP root ${root.path} is not a directory. Grant \"All files access\" in Settings, or pick a different directory.")
    }
    if (!root.canRead()) {
        throw IOException("SFTP root ${root.path} is not readable by EarthQuack.")
    }
}
