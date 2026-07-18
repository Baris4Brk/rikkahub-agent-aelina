package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension

/**
 * The single SQLite runtime adapter for both the production Room database and migration tests.
 *
 * Android's framework SQLite is device-dependent and does not provide FTS5 on the HONOR test
 * device. Reusing this factory keeps migration verification on the same bundled FTS5/simple-
 * tokenizer runtime that opens the real application database.
 */
fun createAppSQLiteOpenHelperFactory(context: Context): SupportSQLiteOpenHelper.Factory =
    RequerySQLiteOpenHelperFactory(
        listOf(
            RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                options.customExtensions.add(
                    SQLiteCustomExtension(
                        context.applicationInfo.nativeLibraryDir + "/libsimple",
                        null,
                    ),
                )
                options
            },
        ),
    )
