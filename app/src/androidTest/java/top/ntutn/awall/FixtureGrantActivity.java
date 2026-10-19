package top.ntutn.awall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Standalone test-APK component: does not rely on the target APK's Kotlin runtime. */
public class FixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        grantUriPermission(getIntent().getStringExtra("recipient"), getIntent().getData(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        finish();
    }
}
