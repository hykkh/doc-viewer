package com.hprograms.docviewer

import java.nio.ByteBuffer

/** LibreOfficeKit calls missing from the stock Java binding (see src/main/cpp/dvlok.c). */
object LokExtra {
    init {
        System.loadLibrary("dvlok")
    }

    /** Returns a document handle for org.libreoffice.kit.Document, or null. */
    @JvmStatic
    external fun documentLoadWithOptions(kit: ByteBuffer, url: String, options: String?): ByteBuffer?
}
