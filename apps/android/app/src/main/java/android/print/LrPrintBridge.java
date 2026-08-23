package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.webkit.WebView;

/**
 * Lives in the android.print package so it can reach the package-private
 * PrintDocumentAdapter.LayoutResultCallback / WriteResultCallback constructors — the
 * only way to drive Chrome's real, page-break-aware print engine to a file without the
 * system print dialog. Produces the same quality as the desktop Playwright page.pdf().
 */
public final class LrPrintBridge {

    public interface ResultCallback {
        void onResult(boolean ok);
    }

    private LrPrintBridge() {}

    public static void print(
            WebView webView,
            PrintAttributes attributes,
            final ParcelFileDescriptor pfd,
            final ResultCallback callback) {
        final PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("LandRecord");
        adapter.onStart();
        adapter.onLayout(
                null,
                attributes,
                new CancellationSignal(),
                new PrintDocumentAdapter.LayoutResultCallback() {
                    @Override
                    public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                        adapter.onWrite(
                                new PageRange[]{PageRange.ALL_PAGES},
                                pfd,
                                new CancellationSignal(),
                                new PrintDocumentAdapter.WriteResultCallback() {
                                    @Override
                                    public void onWriteFinished(PageRange[] pages) {
                                        adapter.onFinish();
                                        callback.onResult(true);
                                    }

                                    @Override
                                    public void onWriteFailed(CharSequence error) {
                                        adapter.onFinish();
                                        callback.onResult(false);
                                    }
                                });
                    }

                    @Override
                    public void onLayoutFailed(CharSequence error) {
                        callback.onResult(false);
                    }
                },
                null);
    }
}
