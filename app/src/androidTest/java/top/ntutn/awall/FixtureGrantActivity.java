package top.ntutn.awall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;

/** Standalone test-APK component: does not rely on the target APK's Kotlin runtime. */
public class FixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        for (String uri : getIntent().getStringArrayExtra("uris")) {
            grantUriPermission(getIntent().getStringExtra("recipient"), Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
