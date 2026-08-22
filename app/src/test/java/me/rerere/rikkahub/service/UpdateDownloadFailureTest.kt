package me.rerere.rikkahub.service

import android.app.DownloadManager
import me.rerere.rikkahub.utils.UpdateDownloadFailureReason
import me.rerere.rikkahub.utils.classifyDownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDownloadFailureTest {
    @Test
    fun httpStatusIsPreferredWhenClassifyingDownloadFailure() {
        assertEquals(
            UpdateDownloadFailureReason.ResourceUnavailable,
            classifyDownloadFailure(DownloadManager.ERROR_UNHANDLED_HTTP_CODE, 404),
        )
        assertEquals(
            UpdateDownloadFailureReason.ServiceUnavailable,
            classifyDownloadFailure(DownloadManager.ERROR_HTTP_DATA_ERROR, 503),
        )
    }

    @Test
    fun systemDownloadReasonsMapToActionableCategories() {
        assertEquals(
            UpdateDownloadFailureReason.InsufficientSpace,
            classifyDownloadFailure(DownloadManager.ERROR_INSUFFICIENT_SPACE, null),
        )
        assertEquals(
            UpdateDownloadFailureReason.FileAlreadyExists,
            classifyDownloadFailure(DownloadManager.ERROR_FILE_ALREADY_EXISTS, null),
        )
        assertEquals(
            UpdateDownloadFailureReason.CannotResume,
            classifyDownloadFailure(DownloadManager.ERROR_CANNOT_RESUME, null),
        )
        assertEquals(
            UpdateDownloadFailureReason.TooManyRedirects,
            classifyDownloadFailure(DownloadManager.ERROR_TOO_MANY_REDIRECTS, null),
        )
        assertEquals(
            UpdateDownloadFailureReason.Network,
            classifyDownloadFailure(DownloadManager.ERROR_HTTP_DATA_ERROR, null),
        )
    }
}
