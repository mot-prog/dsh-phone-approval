package com.dsh.phoneapproval;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Configure server/token, start the relay service, and answer approvals inline. */
public class MainActivity extends Activity {

    private EditText serverInput;
    private EditText tokenInput;
    private TextView statusView;
    private LinearLayout listContainer;
    private boolean dark;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(ApprovalService.EXTRA_STATUS);
            if (status != null) statusView.setText(status);
            String json = intent.getStringExtra(ApprovalService.EXTRA_PENDING_JSON);
            if (json != null) renderApprovals(json);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Follow the system light/dark setting (DeviceDefault dark follows OEM dark mode).
        dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_DeviceDefault : android.R.style.Theme_DeviceDefault_Light);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        serverInput = new EditText(this);
        serverInput.setHint("Server URL, e.g. http://192.168.0.12:8790");
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(serverInput, matchWrap());

        tokenInput = new EditText(this);
        tokenInput.setHint("Token (from ~/.dsh/phone-approval/config.json on the PC)");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenInput, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button connect = new Button(this);
        connect.setText("Save & Connect");
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndConnect();
            }
        });
        Button disconnect = new Button(this);
        disconnect.setText("Disconnect");
        disconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(new Intent(MainActivity.this, ApprovalService.class)
                        .setAction(ApprovalService.ACTION_STOP));
            }
        });
        buttons.addView(connect, wrapWrap());
        buttons.addView(disconnect, wrapWrap());
        root.addView(buttons, matchWrap());

        statusView = new TextView(this);
        statusView.setText("not connected");
        statusView.setTextSize(14f);
        int vpad = dp(8);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = vpad;
        statusParams.bottomMargin = vpad;
        root.addView(statusView, statusParams);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        SharedPreferences prefs = prefs();
        serverInput.setText(prefs.getString("server", ""));
        tokenInput.setText(prefs.getString("token", ""));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ApprovalService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        // Pull the service's current pending snapshot: broadcasts only cover
        // changes that happened while this activity was resumed.
        startService(new Intent(this, ApprovalService.class)
                .setAction(ApprovalService.ACTION_SNAPSHOT));
    }

    @Override
    protected void onPause() {
        unregisterReceiver(stateReceiver);
        super.onPause();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("dsh", Context.MODE_PRIVATE);
    }

    private void saveAndConnect() {
        String server = serverInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            statusView.setText("server must start with http:// or https://");
            return;
        }
        if (token.isEmpty()) {
            statusView.setText("token is required");
            return;
        }
        prefs().edit().putString("server", server).putString("token", token).apply();
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(new Intent(this, ApprovalService.class)
                    .setAction(ApprovalService.ACTION_START));
        } else {
            startService(new Intent(this, ApprovalService.class)
                    .setAction(ApprovalService.ACTION_START));
        }
        statusView.setText("connecting to " + server + " …");
        askIgnoreBatteryOptimizations();
    }

    private void askIgnoreBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            }
        } catch (Exception ignored) {
            // OEM without the settings screen: user can whitelist manually
        }
    }

    private void renderApprovals(String json) {
        listContainer.removeAllViews();
        List<JSONObject> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) items.add(array.getJSONObject(i));
        } catch (Exception ignored) {
        }
        if (items.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No pending approvals.");
            listContainer.addView(none, matchWrap());
            return;
        }
        for (JSONObject item : items) {
            listContainer.addView(buildCard(item), matchWrap());
        }
    }

    private View buildCard(JSONObject item) {
        final String id = item.optString("id");
        String tool = item.optString("toolName", "tool");
        String reason = item.optString("reason", "");
        String command = item.optString("command", "");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(dark ? 0x1AFFFFFF : 0x11000000);
        int pad = dp(10);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("⚠ " + tool);
        title.setTextSize(17f);
        card.addView(title, matchWrap());

        if (!command.isEmpty()) {
            TextView cmd = new TextView(this);
            cmd.setText(command);
            cmd.setTypeface(android.graphics.Typeface.MONOSPACE);
            cmd.setTextSize(13f);
            cmd.setMaxLines(12);
            cmd.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cmd.setBackgroundColor(dark ? 0x33000000 : 0x0A000000);
            int cp = dp(6);
            cmd.setPadding(cp, cp, cp, cp);
            LinearLayout.LayoutParams cmdParams = matchWrap();
            cmdParams.topMargin = dp(6);
            card.addView(cmd, cmdParams);
        }

        if (!reason.isEmpty()) {
            TextView reasonView = new TextView(this);
            reasonView.setText(reason);
            reasonView.setTextSize(14f);
            card.addView(reasonView, matchWrap());
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        Button allow = new Button(this);
        allow.setText("Allow");
        allow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answer(id, "allow-once");
            }
        });
        Button deny = new Button(this);
        deny.setText("Deny");
        deny.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answer(id, "reject");
            }
        });
        row.addView(allow, wrapWrap());
        row.addView(deny, wrapWrap());
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(4);
        card.addView(row, rowParams);

        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        return card;
    }

    private void answer(String id, String outcome) {
        statusView.setText("sending " + outcome + " …");
        startService(new Intent(this, ApprovalService.class)
                .setAction(ApprovalService.ACTION_ANSWER)
                .putExtra(ApprovalService.EXTRA_ID, id)
                .putExtra(ApprovalService.EXTRA_OUTCOME, outcome));
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
