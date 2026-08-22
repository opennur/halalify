package io.shellify.app.data.local.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.shellify.app.data.local.AppDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that existing apps receive the enabled content-protection defaults during upgrade. */
@RunWith(AndroidJUnit4::class)
class Migration6To7Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(TEST_PASSPHRASE.copyOf()),
    )

    @Before
    fun loadSqlCipherLibrary() {
        System.loadLibrary("sqlcipher")
    }

    @Test
    fun migrate6To7_addsContentProtectionWithSafeDefaults() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                """INSERT INTO web_apps (
                    id, name, url, isolationId,
                    adBlockEnabled, adBlockAllowUserToggle, adBlockCustomRules,
                    translateEnabled, translateTarget, translateEngine,
                    showTranslateButton, autoTranslateOnLoad,
                    libreTranslateUrl, libreTranslateApiKey,
                    uaMode, createdAt, updatedAt,
                    lockType, engineType, wipeOnFailedAttempts,
                    isFullscreen, fullscreenShowStatusBar, fullscreenShowNavBar,
                    fullscreenShowTopToolbar, has_launcher_shortcut, show_control_center,
                    notification_permission, dnd_start_hour, dnd_end_hour,
                    background_notifications_enabled, swipe_to_refresh_enabled,
                    stealth_mode, cookie_auto_wipe, always_incognito,
                    tracker_blocking_enabled, use_tor, preserve_tor_identity
                ) VALUES (
                    1, 'ExistingApp', 'https://example.com', 'iso-existing',
                    1, 0, '',
                    0, 'en', 'AUTO',
                    1, 0,
                    'https://libretranslate.com', '',
                    'CHROME_MOBILE', 0, 0,
                    'NONE', 'SYSTEM_WEBVIEW', 0,
                    0, 0, 0,
                    0, 0, 1,
                    'NOT_ASKED', -1, -1,
                    0, 1,
                    0, 0, 0,
                    0, 0, 0
                )"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7).use { db ->
            db.query(
                "SELECT content_protection_enabled, content_protection_blur_images, " +
                    "content_protection_blur_videos, content_protection_blur_amount, " +
                    "content_protection_grayscale, content_protection_strictness, " +
                    "content_protection_blur_male, content_protection_blur_female, " +
                    "content_protection_startup_blur, content_protection_hover_reveal, " +
                    "content_protection_whitelist FROM web_apps WHERE id = 1"
            ).use { cursor ->
                assertTrue("web_apps row not found after migration 6→7", cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(20, cursor.getInt(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals(0.5f, cursor.getFloat(5), 0.001f)
                assertEquals(0, cursor.getInt(6))
                assertEquals(1, cursor.getInt(7))
                assertEquals(1, cursor.getInt(8))
                assertEquals(1, cursor.getInt(9))
                assertEquals("", cursor.getString(10))
            }
        }
    }

    companion object {
        private const val TEST_DB = "migration-6-7-test.db"
        private val TEST_PASSPHRASE = "shellify-test-passphrase".toByteArray()
    }
}
