// Calls LibreOfficeKit's documentLoadWithOptions, which the stock Java
// binding (org.libreoffice.kit.Office) does not expose. We need it for the
// "Batch" option: it makes LibreOffice answer its own dialogs ("open
// read-only?", macro warnings, repair prompts) with the default instead of
// spinning forever waiting for a window that cannot appear on Android.
#include <jni.h>
#include <stddef.h>

typedef struct LokKit LokKit;
typedef struct LokDoc LokDoc;

// Leading members of _LibreOfficeKitClass (include/LibreOfficeKit/LibreOfficeKit.h).
typedef struct {
    size_t nSize;
    void (*destroy)(LokKit *);
    LokDoc *(*documentLoad)(LokKit *, const char *);
    char *(*getError)(LokKit *);
    LokDoc *(*documentLoadWithOptions)(LokKit *, const char *, const char *);
} LokKitClass;

struct LokKit {
    LokKitClass *pClass;
};

struct LokDoc {
    void *pClass;
};

JNIEXPORT jobject JNICALL
Java_com_hprograms_docviewer_LokExtra_documentLoadWithOptions(JNIEnv *env, jclass cls, jobject kitHandle, jstring url, jstring options) {
    (void) cls;
    LokKit *kit = (LokKit *) (*env)->GetDirectBufferAddress(env, kitHandle);
    if (kit == NULL || kit->pClass == NULL) return NULL;
    if (kit->pClass->nSize < offsetof(LokKitClass, documentLoadWithOptions) + sizeof(void *)) return NULL;

    const char *u = (*env)->GetStringUTFChars(env, url, NULL);
    const char *o = options ? (*env)->GetStringUTFChars(env, options, NULL) : NULL;
    LokDoc *doc = kit->pClass->documentLoadWithOptions(kit, u, o);
    (*env)->ReleaseStringUTFChars(env, url, u);
    if (o) (*env)->ReleaseStringUTFChars(env, options, o);

    if (doc == NULL) return NULL;
    return (*env)->NewDirectByteBuffer(env, doc, sizeof(LokDoc));
}
