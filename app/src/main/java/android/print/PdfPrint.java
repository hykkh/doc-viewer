package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Drives a PrintDocumentAdapter straight into a PDF file, without the system
 * print dialog. Lives in android.print because the layout/write callback
 * constructors are package-private.
 */
public final class PdfPrint {

    public interface Done {
        void onDone(String error); // null on success
    }

    private PdfPrint() {
    }

    public static void write(final PrintDocumentAdapter adapter, PrintAttributes attrs, final File out, final Done done) {
        adapter.onStart();
        adapter.onLayout(null, attrs, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                final ParcelFileDescriptor fd;
                try {
                    fd = ParcelFileDescriptor.open(out,
                            ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
                } catch (Exception e) {
                    done.onDone(String.valueOf(e.getMessage()));
                    return;
                }
                adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, fd, new CancellationSignal(),
                        new PrintDocumentAdapter.WriteResultCallback() {
                            @Override
                            public void onWriteFinished(PageRange[] pages) {
                                close(fd);
                                adapter.onFinish();
                                done.onDone(null);
                            }

                            @Override
                            public void onWriteFailed(CharSequence error) {
                                close(fd);
                                adapter.onFinish();
                                done.onDone(String.valueOf(error));
                            }

                            @Override
                            public void onWriteCancelled() {
                                close(fd);
                                adapter.onFinish();
                                done.onDone("cancelled");
                            }
                        });
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                adapter.onFinish();
                done.onDone(String.valueOf(error));
            }
        }, null);
    }

    private static void close(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (Exception ignored) {
        }
    }
}
