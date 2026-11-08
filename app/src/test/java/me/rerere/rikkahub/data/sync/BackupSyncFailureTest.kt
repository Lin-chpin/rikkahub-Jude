package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.s3.S3Exception
import me.rerere.rikkahub.data.sync.webdav.WebDavException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSyncFailureTest {
    @Test
    fun knownHttpStatusesArePreservedWithBackupServiceScope() {
        assertTrue(WebDavException("private body", 401, "private body")
            .backupSyncErrorMessage().contains("身份验证失败（HTTP 401）"))
        assertTrue(S3Exception("private body", "private body", 403)
            .backupSyncErrorMessage().contains("拒绝访问（HTTP 403）"))
        assertTrue(S3Exception("private body", "private body", 503)
            .backupSyncErrorMessage().contains("具体内部原因无法确认"))
    }

    @Test
    fun localIoFailureIsNotReportedAsRemoteConnectionFailure() {
        assertTrue(ConnectException("refused").backupSyncErrorMessage().contains("无法连接备份服务"))
        assertTrue(SocketException("connection reset").backupSyncErrorMessage().contains("连接中断，具体阶段无法确认"))
        assertTrue(IOException("local file failed").backupSyncErrorMessage().contains("传输阶段失败"))
        assertFalse(IOException("local file failed").backupSyncErrorMessage().contains("无法连接备份服务"))
    }

    @Test
    fun safeDiagnosticKeepsOperationAndStatusWithoutExceptionText() {
        val error = S3Exception("https://store.invalid?key=secret", "private response body", 429)

        val diagnostic = error.backupSyncDiagnostic("restore")

        assertTrue(diagnostic.contains("operation=restore"))
        assertTrue(diagnostic.contains("S3Exception"))
        assertTrue(diagnostic.contains("http_status=429"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("private response body"))
    }
}
